/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.bluetooth.directbt.internal.reconcile;

import static org.junit.jupiter.api.Assertions.*;
import static org.openhab.binding.bluetooth.directbt.internal.reconcile.AdapterLeaseCoordinator.*;
import static org.openhab.binding.bluetooth.directbt.internal.reconcile.ReconcileTestSupport.*;

import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;

/**
 * Regression harness for {@link AdapterLeaseCoordinator} — the radio arbitration that replaced the static
 * "discovery always wins" rollup after the 2026-07-16 2h17m starvation outage (a permanently invisible tank
 * held the scan on; the HP was gated on "waiting for scan to stop" forever), and the hunted-device
 * escalation ladder that cures the stuck-initiator/zombie-connection controller states.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
class AdapterLeaseCoordinatorTest {

    private static final long STEP_MS = 500;

    private final MutableClock clock = new MutableClock(START);
    private final AtomicInteger sweeps = new AtomicInteger();
    private final AtomicInteger resets = new AtomicInteger();
    private final AdapterLeaseCoordinator c = new AdapterLeaseCoordinator(logger(), budget(clock),
            sweeps::incrementAndGet, resets::incrementAndGet, clock);

    /** decide() under sustained discovery/connect contention. */
    private boolean contended() {
        return c.decide(true, false, false, false, true);
    }

    // --- contention time-slicing ------------------------------------------------------------------

    @Test
    void contentionStartsWithAConnectSliceAndAlternatesBounded() {
        // The establishing device (acute, seconds) gets the radio before the hunt (chronic, open-ended).
        assertFalse(contended(), "contention must begin with a connect slice");

        long scanOffMs = 0;
        while (!contended()) {
            clock.advance(STEP_MS);
            scanOffMs += STEP_MS;
            assertTrue(scanOffMs <= CONNECT_SLICE_MS + STEP_MS, "the connect slice must be bounded");
        }
        long scanOnMs = 0;
        while (contended()) {
            clock.advance(STEP_MS);
            scanOnMs += STEP_MS;
            assertTrue(scanOnMs <= DISCOVERY_SLICE_MS + STEP_MS, "the discovery slice must be bounded — "
                    + "discovery holding the radio forever is the 2h17m starvation bug");
        }
        // And it keeps alternating: neither demand ever starves the other.
        assertFalse(contended());
    }

    @Test
    void connectingSuppressesTheScanEvenMidDiscoverySlice() {
        while (!contended()) {
            clock.advance(STEP_MS); // reach the discovery slice
        }
        assertFalse(c.decide(true, false, false, true, true),
                "an in-flight create-connection always wins the radio (controller rejects scan+create)");
    }

    @Test
    void contentionEndReturnsToTheStaticRules() {
        assertFalse(contended()); // in contention (connect slice)

        // Hunted device found: only establishing remains -> inbox discovery yields, scan stays off.
        assertFalse(c.decide(false, true, false, false, true));

        // Establishment finished: only the hunt remains -> scan on, uncontended.
        assertTrue(c.decide(true, false, false, false, false));
    }

    // --- hunted-device starvation ladder -------------------------------------------------------------

    @Test
    void sustainedHuntingEscalatesSweepThenOneBudgetedReset() {
        for (long t = 0; t < LADDER_RUNG_MS - STEP_MS; t += STEP_MS) {
            c.decide(true, false, false, false, false);
            clock.advance(STEP_MS);
        }
        assertEquals(0, sweeps.get(), "hunting below the rung threshold is normal cold-start behaviour");

        for (long t = 0; t < LADDER_RUNG_MS + 2 * STEP_MS; t += STEP_MS) {
            c.decide(true, false, false, false, false);
            clock.advance(STEP_MS);
        }
        assertEquals(1, sweeps.get(), "rung 1: recovery sweep (clears stuck pending create-connections)");
        assertEquals(1, resets.get(), "rung 2: adapter reset (the only cure for a zombie LL connection)");

        for (long t = 0; t < 4 * LADDER_RUNG_MS; t += STEP_MS) {
            c.decide(true, false, false, false, false);
            clock.advance(STEP_MS);
        }
        assertEquals(1, sweeps.get(), "one sweep per hunting episode — an absent peer is not an adapter fault");
        assertEquals(1, resets.get(), "one reset per hunting episode — never reset forever under a dead battery");
    }

    @Test
    void deniedResetBudgetDoesNotConsumeTheResetRung() {
        ResetBudget budget = new ResetBudget(BUDGET_COOLDOWN_MS, clock);
        AtomicInteger localSweeps = new AtomicInteger();
        AtomicInteger localResets = new AtomicInteger();
        AdapterLeaseCoordinator coordinator = new AdapterLeaseCoordinator(logger(), budget,
                localSweeps::incrementAndGet, localResets::incrementAndGet, clock);

        for (long t = 0; t < 2 * LADDER_RUNG_MS - STEP_MS; t += STEP_MS) {
            coordinator.decide(true, false, false, false, false);
            clock.advance(STEP_MS);
        }
        assertEquals(1, localSweeps.get(), "sweep rung still fires");
        assertTrue(budget.tryReset("other"), "another requester consumes the shared reset budget just before rung 2");

        for (long t = 0; t < 2 * STEP_MS; t += STEP_MS) {
            coordinator.decide(true, false, false, false, false);
            clock.advance(STEP_MS);
        }
        assertEquals(1, localSweeps.get(), "sweep rung still fires");
        assertEquals(0, localResets.get(), "reset rung must not fire while the shared budget denies it");

        clock.advance(BUDGET_COOLDOWN_MS);
        coordinator.decide(true, false, false, false, false);
        assertEquals(1, localResets.get(), "the reset rung must retry after the budget becomes available");

        for (long t = 0; t < 2 * LADDER_RUNG_MS; t += STEP_MS) {
            coordinator.decide(true, false, false, false, false);
            clock.advance(STEP_MS);
        }
        assertEquals(1, localResets.get(), "after one successful reset, the episode is exhausted");
    }

    @Test
    void findingTheDeviceEndsTheEpisodeAndArmsAFreshLadder() {
        for (long t = 0; t < LADDER_RUNG_MS / 2; t += STEP_MS) {
            c.decide(true, false, false, false, false);
            clock.advance(STEP_MS);
        }
        c.decide(false, false, false, false, false); // found: episode over
        assertEquals(0, sweeps.get());

        // A fresh episode must earn its own full rung interval before escalating.
        for (long t = 0; t < LADDER_RUNG_MS - STEP_MS; t += STEP_MS) {
            c.decide(true, false, false, false, false);
            clock.advance(STEP_MS);
        }
        assertEquals(0, sweeps.get(), "the ladder must restart per episode, not carry stale age");

        for (long t = 0; t < 2 * STEP_MS; t += STEP_MS) {
            c.decide(true, false, false, false, false);
            clock.advance(STEP_MS);
        }
        assertEquals(1, sweeps.get());
    }

    @Test
    void inboxOnlyDiscoveryNeverEscalates() {
        for (long t = 0; t < 3 * LADDER_RUNG_MS; t += STEP_MS) {
            assertTrue(c.decide(false, true, false, false, false), "idle background discovery scans");
            clock.advance(STEP_MS);
        }
        assertEquals(0, sweeps.get(), "the inbox hunting nothing in particular is not starvation");
        assertEquals(0, resets.get());
    }
}
