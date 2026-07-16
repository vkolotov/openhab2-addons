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

import java.time.Clock;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.slf4j.Logger;

/**
 * Owns the adapter's radio-arbitration policy: who gets the single radio when discovery and
 * connection-establishment demands conflict, and when a device that can never be discovered warrants
 * escalation. First slice of the adapter coordinator from the frozen FSM/actor design
 * (docs/directbt-device-fsm-actor-proposal-2026-07-16.md, constraints 2 and 5), introduced under the existing
 * reconciler: the bridge feeds it the per-tick demand rollup and it answers "should the scan be on".
 * Single-threaded by construction — only ever called from the bridge reconcile tick.
 *
 * <p>
 * Policies:
 * <ul>
 * <li><b>No contention:</b> the pre-existing {@link #scanWanted} rules apply unchanged (scan to discover, inbox
 * discovery yields to an establishing device, everything yields to an in-flight create-connection).</li>
 * <li><b>Contention time-slice:</b> when one device needs DISCOVERY (no handle) while another is ESTABLISHING
 * (holds a handle, needs the scan off to connect), neither may hold the radio indefinitely. The old rollup let
 * discovery win statically, which starved every connect for as long as the hunted device stayed invisible —
 * the 2026-07-16 16:43-19:00 outage (tank held by a zombie LL connection, HP gated on "waiting for scan to
 * stop" for 2h17m). Now the radio alternates: a connect slice first (the acute need), then a discovery slice
 * (the chronic hunt), bounded both ways.</li>
 * <li><b>Starvation ladder:</b> a wanted device continuously undiscovered for {@link #LADDER_RUNG_MS} gets a
 * budgeted recovery ladder — rung 1: best-effort disconnect sweep (clears stuck pending create-connections;
 * a no-op against a controller-held zombie), rung 2: adapter reset (the only host-side action that reaches a
 * zombie LL connection). One sweep and one reset per hunting episode: a genuinely absent peer (dead battery)
 * must not reset the adapter forever under the healthy devices.</li>
 * </ul>
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
public class AdapterLeaseCoordinator {

    // Contention slice lengths. A connect slice fits ~2 attempt cycles (2s retry spacing + establishment);
    // a discovery slice is long enough for a slow advertiser (battery gauges advertise every few seconds)
    // to be heard several times at 50% scan duty.
    static final long CONNECT_SLICE_MS = 16_000;
    static final long DISCOVERY_SLICE_MS = 30_000;

    // How long a wanted device may stay continuously undiscovered before each escalation rung fires.
    static final long LADDER_RUNG_MS = 180_000;

    private final Logger logger;
    private final ResetBudget resetBudget;
    private final Runnable recoverySweep;
    private final Runnable requestAdapterReset;
    private final Clock clock;

    // Contention slice state (0 = not in contention).
    private long sliceStartedAt;
    private boolean discoverySlice;

    // Starvation-ladder state (0 = nothing being hunted).
    private long huntingSince;
    private int ladderRung;

    public AdapterLeaseCoordinator(Logger logger, ResetBudget resetBudget, Runnable recoverySweep,
            Runnable requestAdapterReset, Clock clock) {
        this.logger = logger;
        this.resetBudget = resetBudget;
        this.recoverySweep = recoverySweep;
        this.requestAdapterReset = requestAdapterReset;
        this.clock = clock;
    }

    /**
     * Decide whether the adapter should be scanning this tick, given the demand rollup. Also advances the
     * starvation ladder. Called once per reconcile tick, on the tick thread only; the escalation runnables it
     * fires must themselves be asynchronous (no native work on this thread).
     */
    public boolean decide(boolean needsDiscovery, boolean backgroundDiscovery, boolean activeScan, boolean connecting,
            boolean establishing) {
        long now = clock.millis();
        updateLadder(needsDiscovery, now);
        if (!connecting && needsDiscovery && establishing) {
            return contentionSlice(now);
        }
        sliceStartedAt = 0;
        return scanWanted(needsDiscovery, backgroundDiscovery, activeScan, connecting, establishing);
    }

    /**
     * Pure decision for the non-contention cases, unchanged from the original bridge rollup.
     * <p>
     * Scan when a configured device needs (re)discovery to get a handle ({@code needsDiscovery}), OR discovery
     * is wanted for the inbox ({@code backgroundDiscovery} config or an in-progress manual scan). BOTH inbox
     * cases yield to a configured device establishing its connection (the controller rejects create-connection
     * while scanning, and a scan restarting between attempts starves the connect), and any scan yields to an
     * in-flight create-connection. The needsDiscovery-vs-establishing conflict is NOT decided here — that is
     * the coordinator's time-slice.
     */
    public static boolean scanWanted(boolean needsDiscovery, boolean backgroundDiscovery, boolean activeScan,
            boolean connecting, boolean establishing) {
        boolean inboxDiscovery = (backgroundDiscovery || activeScan) && !establishing;
        boolean discoveryWanted = needsDiscovery || inboxDiscovery;
        return discoveryWanted && !connecting;
    }

    private boolean contentionSlice(long now) {
        if (sliceStartedAt == 0) {
            // The establishing device gets the radio first: connecting takes seconds, hunting is open-ended.
            sliceStartedAt = now;
            discoverySlice = false;
            logger.debug("[coordinator] discovery/connect contention: CONNECT slice for {}ms", CONNECT_SLICE_MS);
            return false;
        }
        long sliceLength = discoverySlice ? DISCOVERY_SLICE_MS : CONNECT_SLICE_MS;
        if (now - sliceStartedAt >= sliceLength) {
            discoverySlice = !discoverySlice;
            sliceStartedAt = now;
            logger.debug("[coordinator] contention slice -> {} for {}ms", discoverySlice ? "DISCOVERY" : "CONNECT",
                    discoverySlice ? DISCOVERY_SLICE_MS : CONNECT_SLICE_MS);
        }
        return discoverySlice;
    }

    private void updateLadder(boolean hunting, long now) {
        if (!hunting) {
            if (huntingSince != 0) {
                logger.debug("[coordinator] hunted device found/cleared after {}ms (ladder rung {})",
                        now - huntingSince, ladderRung);
            }
            huntingSince = 0;
            ladderRung = 0;
            return;
        }
        if (huntingSince == 0) {
            huntingSince = now;
            return;
        }
        if (ladderRung >= 2) {
            return; // one sweep + one reset per hunting episode; an absent peer is not an adapter fault
        }
        long nextRungDue = huntingSince + (ladderRung + 1) * LADDER_RUNG_MS;
        if (now < nextRungDue) {
            return;
        }
        if (ladderRung == 0) {
            ladderRung = 1;
            logger.warn("[coordinator] wanted device undiscovered for {}ms; recovery sweep "
                    + "(clears stuck pending create-connections)", now - huntingSince);
            recoverySweep.run();
            return;
        }
        if (resetBudget.tryReset("coordinator")) {
            ladderRung = 2;
            logger.warn("[coordinator] wanted device still undiscovered after {}ms and a sweep; adapter reset "
                    + "(the only cure for a controller-held zombie connection)", now - huntingSince);
            requestAdapterReset.run();
        }
    }
}
