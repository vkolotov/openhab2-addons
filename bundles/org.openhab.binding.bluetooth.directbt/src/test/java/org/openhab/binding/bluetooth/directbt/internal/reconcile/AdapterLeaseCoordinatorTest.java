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
import static org.openhab.binding.bluetooth.directbt.internal.reconcile.ReconcileTestSupport.*;
import static org.openhab.binding.bluetooth.directbt.internal.reconcile.adapter.AdapterLeaseCoordinator.*;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.direct_bt.HCIStatusCode;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.adapter.*;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.device.*;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.effect.*;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.event.*;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.port.*;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.procedure.*;

/**
 * Regression harness for {@link AdapterLeaseCoordinator}: bounded radio arbitration between discovery and
 * connection establishment, plus evidence-gated recovery for a controller-side connection missing from host state.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
class AdapterLeaseCoordinatorTest {

    private static final long STEP_MS = 500;
    private static final String TARGET_DEVICE = "target-device";
    private static final String PEER_DEVICE = "peer-device";
    private static final String OTHER_ADVERTISER = "other-advertiser";

    private final MutableClock clock = new MutableClock(START);
    private final AtomicInteger sweeps = new AtomicInteger();
    private final AtomicInteger resets = new AtomicInteger();
    private final AtomicReference<String> sweepTarget = new AtomicReference<>("");
    private final AdapterLeaseCoordinator c = new AdapterLeaseCoordinator(logger(), budget(clock), deviceId -> {
        sweepTarget.set(deviceId);
        sweeps.incrementAndGet();
    }, resets::incrementAndGet, clock);

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
            assertTrue(scanOnMs <= DISCOVERY_SLICE_MS + STEP_MS,
                    "the discovery slice must be bounded so connection establishment cannot starve");
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

    // --- evidence-gated recovery ladder -----------------------------------------------------------

    @Test
    void ordinaryAbsenceNeverEscalates() {
        decideFor(24 * 60 * 60 * 1000L, Map.of(TARGET_DEVICE, 1L), Set.of());

        assertEquals(0, sweeps.get(), "a powered-off/dead-battery device is a valid steady state");
        assertEquals(0, resets.get(), "absence alone must never reset a shared adapter");
    }

    @Test
    void establishedConnectionTimeoutDoesNotArmRecovery() {
        c.noteAdvertisement(TARGET_DEVICE, true);
        c.noteConnectionFailure(TARGET_DEVICE, 7, HCIStatusCode.CONNECTION_TIMEOUT, true);
        c.noteAdvertisement(OTHER_ADVERTISER, false);

        decideFor(24 * 60 * 60 * 1000L, Map.of(TARGET_DEVICE, 7L), Set.of());

        assertEquals(0, sweeps.get());
        assertEquals(0, resets.get(), "an established-link timeout must be non-destructive");
    }

    @Test
    void ambiguousFailureWithoutOtherAdvertisementsDoesNotEscalate() {
        armPossibleConnectionLeak(TARGET_DEVICE, 3);

        decideFor(3 * LADDER_RUNG_MS, Map.of(TARGET_DEVICE, 3L), Set.of());

        assertEquals(0, sweeps.get(), "silence is not selective until other advertisements prove scan liveness");
        assertEquals(0, resets.get());
    }

    @Test
    void unconfiguredAdvertisementIsNotRetainedAsConnectEvidence() {
        c.noteAdvertisement("transient-rpa", false);
        c.noteConnectionFailure("transient-rpa", 3, HCIStatusCode.CONNECTION_EST_FAILED_OR_SYNC_TIMEOUT, true);
        c.noteAdvertisement(OTHER_ADVERTISER, false);

        decideFor(3 * LADDER_RUNG_MS, Map.of("transient-rpa", 3L), Set.of(PEER_DEVICE));

        assertEquals(0, sweeps.get());
        assertEquals(0, resets.get(), "arbitrary rotating advertisers must not become recovery targets");
    }

    @Test
    void selectiveDisappearanceGetsTargetedSweepButNoResetWithoutAdapterWideImpact() {
        armPossibleConnectionLeak(TARGET_DEVICE, 3);
        c.noteAdvertisement(OTHER_ADVERTISER, false);

        decideFor(24 * 60 * 60 * 1000L, Map.of(TARGET_DEVICE, 3L), Set.of());

        assertEquals(1, sweeps.get(), "the connection-leak signature retains its cheap recovery rung");
        assertEquals(TARGET_DEVICE, sweepTarget.get(), "the cheap rung must clean up only the suspected device");
        assertEquals(0, resets.get(), "one selectively absent device must not reset healthy peers");
    }

    @Test
    void selectiveDisappearancePlusAnotherContinuouslyStarvedDeviceResetsOnce() {
        armPossibleConnectionLeak(TARGET_DEVICE, 3);
        c.noteAdvertisement(OTHER_ADVERTISER, false);

        decideFor(3 * LADDER_RUNG_MS, Map.of(TARGET_DEVICE, 3L), Set.of(PEER_DEVICE));

        assertEquals(1, sweeps.get());
        assertEquals(1, resets.get(), "sustained impact on a second device makes this adapter-wide");

        decideFor(4 * LADDER_RUNG_MS, Map.of(TARGET_DEVICE, 3L), Set.of(PEER_DEVICE));
        assertEquals(1, sweeps.get());
        assertEquals(1, resets.get(), "one sweep and reset per evidence generation");
    }

    @Test
    void deniedResetBudgetRetriesWithoutRepeatingTheSweep() {
        ResetBudget budget = new ResetBudget(BUDGET_COOLDOWN_MS, clock);
        AtomicInteger localSweeps = new AtomicInteger();
        AtomicInteger localResets = new AtomicInteger();
        AdapterLeaseCoordinator coordinator = new AdapterLeaseCoordinator(logger(), budget,
                ignored -> localSweeps.incrementAndGet(), localResets::incrementAndGet, clock);
        coordinator.noteAdvertisement(TARGET_DEVICE, true);
        coordinator.noteConnectionFailure(TARGET_DEVICE, 3, HCIStatusCode.CONNECTION_EST_FAILED_OR_SYNC_TIMEOUT, true);
        coordinator.noteAdvertisement(OTHER_ADVERTISER, false);

        for (long elapsed = 0; elapsed < 2 * LADDER_RUNG_MS; elapsed += STEP_MS) {
            coordinator.decide(true, false, false, false, true, Map.of(TARGET_DEVICE, 3L), Set.of(PEER_DEVICE));
            clock.advance(STEP_MS);
        }
        assertTrue(budget.tryReset("other"), "another requester consumes the shared reset budget just before rung 2");
        coordinator.decide(true, false, false, false, true, Map.of(TARGET_DEVICE, 3L), Set.of(PEER_DEVICE));
        assertEquals(1, localSweeps.get());
        assertEquals(0, localResets.get(), "a denied reset must remain pending behind the shared budget");

        clock.advance(BUDGET_COOLDOWN_MS);
        coordinator.decide(true, false, false, false, true, Map.of(TARGET_DEVICE, 3L), Set.of(PEER_DEVICE));
        assertEquals(1, localSweeps.get(), "retrying the reset must not repeat the cheap rung");
        assertEquals(1, localResets.get(), "the reset may proceed once the shared budget is available");
    }

    @Test
    void targetAdvertisementClearsConnectionLeakEvidence() {
        armPossibleConnectionLeak(TARGET_DEVICE, 3);
        c.noteAdvertisement(OTHER_ADVERTISER, false);
        c.noteAdvertisement(TARGET_DEVICE, true);

        decideFor(3 * LADDER_RUNG_MS, Map.of(TARGET_DEVICE, 3L), Set.of(PEER_DEVICE));

        assertEquals(0, sweeps.get());
        assertEquals(0, resets.get(), "fresh target evidence disproves the connection-leak hypothesis");
    }

    @Test
    void deviceGenerationChangeClearsConnectionLeakEvidence() {
        armPossibleConnectionLeak(TARGET_DEVICE, 3);
        c.noteAdvertisement(OTHER_ADVERTISER, false);

        decideFor(3 * LADDER_RUNG_MS, Map.of(TARGET_DEVICE, 4L), Set.of(PEER_DEVICE));

        assertEquals(0, sweeps.get());
        assertEquals(0, resets.get(), "recovery evidence must never cross connection generations");
    }

    @Test
    void inboxOnlyDiscoveryNeverEscalates() {
        decideFor(3 * LADDER_RUNG_MS, Map.of(), Set.of());
        assertEquals(0, sweeps.get());
        assertEquals(0, resets.get());
    }

    private void armPossibleConnectionLeak(String deviceId, long generation) {
        c.noteAdvertisement(deviceId, true);
        c.noteConnectionFailure(deviceId, generation, HCIStatusCode.CONNECTION_EST_FAILED_OR_SYNC_TIMEOUT, true);
    }

    private void decideFor(long durationMs, Map<String, Long> huntingDevices, Set<String> establishingDevices) {
        for (long elapsed = 0; elapsed <= durationMs; elapsed += STEP_MS) {
            c.decide(!huntingDevices.isEmpty(), false, false, false, !establishingDevices.isEmpty(), huntingDevices,
                    establishingDevices);
            clock.advance(STEP_MS);
        }
    }
}
