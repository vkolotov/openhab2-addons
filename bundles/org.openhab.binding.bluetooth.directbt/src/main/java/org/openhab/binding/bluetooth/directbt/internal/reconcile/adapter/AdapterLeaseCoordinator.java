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
package org.openhab.binding.bluetooth.directbt.internal.reconcile.adapter;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.direct_bt.HCIStatusCode;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.ResetBudget;
import org.slf4j.Logger;

/**
 * Owns the adapter's radio-arbitration policy: who gets the single radio when discovery and
 * connection-establishment demands conflict, and when positive controller-failure evidence warrants escalation.
 * The bridge feeds it the per-tick demand rollup and it answers "should the scan be on". Single-threaded by
 * construction — only ever called from the bridge reconcile tick.
 *
 * <p>
 * Policies:
 * <ul>
 * <li><b>No contention:</b> the pre-existing {@link #scanWanted} rules apply unchanged (scan to discover, inbox
 * discovery yields to an establishing device, everything yields to an in-flight create-connection).</li>
 * <li><b>Contention time-slice:</b> when one device needs DISCOVERY (no handle) while another is ESTABLISHING
 * (holds a handle, needs the scan off to connect), neither may hold the radio indefinitely. The old rollup let
 * discovery win statically, which starved every connect for as long as the undiscovered device stayed invisible.
 * The radio therefore alternates: a connect slice first (the acute need), then a discovery slice (the chronic
 * need), bounded both ways.</li>
 * <li><b>Evidence-gated recovery ladder:</b> ordinary absence is a valid steady state and never arms recovery.
 * An ambiguous {@code 0x3e} connect failure followed by selective disappearance while other advertisements
 * continue suggests that the controller retained a connection that the host does not know about. It gets one
 * best-effort cleanup; adapter reset additionally requires another device to remain unable to establish, so one
 * offline peripheral can never reset healthy peers.</li>
 * </ul>
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
public class AdapterLeaseCoordinator {

    // Contention slice lengths. A connect slice fits ~2 attempt cycles (2s retry spacing + establishment);
    // a discovery slice is long enough for a slow advertiser (battery gauges advertise every few seconds)
    // to be heard several times at 50% scan duty.
    public static final long CONNECT_SLICE_MS = 16_000;
    public static final long DISCOVERY_SLICE_MS = 30_000;

    // How long a wanted device may stay continuously undiscovered before each escalation rung fires.
    public static final long LADDER_RUNG_MS = 180_000;
    // A connect attempt must follow a recent advertisement to be eligible for the selective-disappearance
    // signature. Handles may wait through one full 30s discovery slice plus connect scheduling.
    public static final long CONNECT_ADVERT_RECENCY_MS = 60_000;

    private final Logger logger;
    private final ResetBudget resetBudget;
    private final Consumer<String> recoverySweep;
    private final Runnable requestAdapterReset;
    private final Clock clock;
    private final AdapterLeaseListener listener;

    // Contention slice state (0 = not in contention).
    private long sliceStartedAt;
    private boolean discoverySlice;

    // Recovery evidence is adapter-coordinator state and is touched only on the bridge scheduler/tick thread.
    private final Map<String, Long> lastAdvertisementAt = new HashMap<>();
    private long advertisementSequence;
    private @Nullable ConnectionLeakEvidence connectionLeakEvidence;
    private int ladderRung;
    private long adapterWideImpactSince;
    private boolean resetSuppressionLogged;

    public AdapterLeaseCoordinator(Logger logger, ResetBudget resetBudget, Consumer<String> recoverySweep,
            Runnable requestAdapterReset, Clock clock) {
        this(logger, resetBudget, recoverySweep, requestAdapterReset, clock, AdapterLeaseListener.NOOP);
    }

    /**
     * @param listener observes arbitration and recovery decisions for metrics; purely passive, and
     *            {@link AdapterLeaseListener#NOOP} when nothing is observing.
     */
    public AdapterLeaseCoordinator(Logger logger, ResetBudget resetBudget, Consumer<String> recoverySweep,
            Runnable requestAdapterReset, Clock clock, AdapterLeaseListener listener) {
        this.logger = logger;
        this.resetBudget = resetBudget;
        this.recoverySweep = recoverySweep;
        this.requestAdapterReset = requestAdapterReset;
        this.clock = clock;
        this.listener = listener;
    }

    /** Runs an observer callback without ever letting it affect arbitration. */
    private void notifyListener(Runnable notification) {
        try {
            notification.run();
        } catch (RuntimeException e) {
            logger.debug("[coordinator] lease listener threw; ignoring", e);
        }
    }

    /**
     * Decide whether the adapter should be scanning this tick, given the demand rollup. This overload performs
     * radio arbitration only; callers with per-device recovery observations use the full overload.
     */
    public boolean decide(boolean needsDiscovery, boolean backgroundDiscovery, boolean activeScan, boolean connecting,
            boolean establishing) {
        return decide(needsDiscovery, backgroundDiscovery, activeScan, connecting, establishing, Map.of(), Set.of());
    }

    /**
     * Decide scan ownership and advance evidence-gated adapter recovery.
     *
     * @param huntingDevices wanted devices currently lacking a native handle, mapped to their actor generation
     * @param establishingDevices devices currently waiting for or establishing a connection/GATT path
     */
    public boolean decide(boolean needsDiscovery, boolean backgroundDiscovery, boolean activeScan, boolean connecting,
            boolean establishing, Map<String, Long> huntingDevices, Set<String> establishingDevices) {
        long now = clock.millis();
        updateRecoveryLadder(huntingDevices, establishingDevices, now);
        boolean contended = !connecting && needsDiscovery && establishing;
        boolean scanning;
        if (contended) {
            scanning = contentionSlice(now);
        } else {
            sliceStartedAt = 0;
            scanning = scanWanted(needsDiscovery, backgroundDiscovery, activeScan, connecting, establishing);
        }
        boolean scanDecision = scanning;
        notifyListener(() -> listener.onScanDecision(scanDecision, contended));
        return scanning;
    }

    /**
     * Record one advertisement. Any advertisement from the suspected target disproves selective disappearance and
     * clears the evidence immediately; advertisements from other devices prove that the adapter scan remains live.
     * Only configured/wanted device timestamps are retained, avoiding an unbounded map of rotating private addresses.
     */
    public void noteAdvertisement(String deviceId, boolean recoveryTarget) {
        long now = clock.millis();
        advertisementSequence++;
        if (recoveryTarget) {
            lastAdvertisementAt.put(deviceId, now);
        }
        ConnectionLeakEvidence evidence = connectionLeakEvidence;
        if (evidence != null && evidence.deviceId.equals(deviceId)) {
            clearRecoveryEvidence("target advertised again", now);
        }
    }

    /**
     * Record a native disconnect/failure callback observed while the actor was waiting for native connection.
     * Only the ambiguous {@code 0x3e} establishment failure can arm controller-side connection-leak evidence.
     */
    public void noteConnectionFailure(String deviceId, long deviceGeneration, HCIStatusCode reason,
            boolean nativeConnectInFlight) {
        if (!nativeConnectInFlight || reason != HCIStatusCode.CONNECTION_EST_FAILED_OR_SYNC_TIMEOUT) {
            return;
        }
        long now = clock.millis();
        Long lastAdvertAt = lastAdvertisementAt.get(deviceId);
        if (lastAdvertAt == null || now - lastAdvertAt > CONNECT_ADVERT_RECENCY_MS) {
            logger.debug("[coordinator] ignoring {} for {} generation {}: no recent target advertisement", reason,
                    deviceId, deviceGeneration);
            return;
        }
        connectionLeakEvidence = new ConnectionLeakEvidence(deviceId, deviceGeneration, now, advertisementSequence);
        ladderRung = 0;
        adapterWideImpactSince = 0;
        resetSuppressionLogged = false;
        logger.warn("[coordinator] possible controller-side connection leak for {} generation {} after {}; "
                + "awaiting selective-disappearance evidence", deviceId, deviceGeneration, reason);
        notifyListener(() -> listener.onRecoveryEvidenceArmed(deviceId));
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
            long heldMs = now - sliceStartedAt;
            discoverySlice = !discoverySlice;
            sliceStartedAt = now;
            boolean nowDiscovery = discoverySlice;
            notifyListener(() -> listener.onSliceSwitched(nowDiscovery, heldMs));
            logger.debug("[coordinator] contention slice -> {} for {}ms", discoverySlice ? "DISCOVERY" : "CONNECT",
                    discoverySlice ? DISCOVERY_SLICE_MS : CONNECT_SLICE_MS);
        }
        return discoverySlice;
    }

    private void updateRecoveryLadder(Map<String, Long> huntingDevices, Set<String> establishingDevices, long now) {
        ConnectionLeakEvidence evidence = connectionLeakEvidence;
        if (evidence == null) {
            return;
        }

        Long huntingGeneration = huntingDevices.get(evidence.deviceId);
        if (huntingGeneration == null) {
            clearRecoveryEvidence("device no longer needs discovery", now);
            return;
        }
        if (huntingGeneration.longValue() != evidence.deviceGeneration) {
            clearRecoveryEvidence("device generation changed", now);
            return;
        }

        // No report after the failure is ambiguous until some OTHER report proves that scanning is alive.
        if (advertisementSequence <= evidence.advertisementSequenceAtFailure) {
            return;
        }

        boolean anotherDeviceEstablishing = establishingDevices.stream()
                .anyMatch(deviceId -> !evidence.deviceId.equals(deviceId));
        if (anotherDeviceEstablishing) {
            if (adapterWideImpactSince == 0) {
                adapterWideImpactSince = now;
            }
        } else {
            adapterWideImpactSince = 0;
        }

        long evidenceAge = now - evidence.startedAt;
        if (ladderRung == 0) {
            if (evidenceAge < LADDER_RUNG_MS) {
                return;
            }
            ladderRung = 1;
            logger.warn(
                    "[coordinator] possible controller-side connection leak for {} generation {} persisted for "
                            + "{}ms; targeted recovery cleanup",
                    evidence.deviceId, evidence.deviceGeneration, evidenceAge);
            notifyListener(() -> listener.onRecoveryEscalated(1, evidence.deviceId, evidenceAge));
            recoverySweep.accept(evidence.deviceId);
            return;
        }
        if (ladderRung >= 2 || evidenceAge < 2 * LADDER_RUNG_MS) {
            return;
        }

        // Reset is adapter-wide and invalidates every healthy connection. Selective absence of one target is not
        // sufficient: another device must itself remain unable to establish for a full rung.
        boolean adapterWideImpact = adapterWideImpactSince != 0 && now - adapterWideImpactSince >= LADDER_RUNG_MS;
        if (!adapterWideImpact) {
            if (!resetSuppressionLogged) {
                resetSuppressionLogged = true;
                logger.warn("[coordinator] suppressing adapter reset for possible connection leak affecting {}: "
                        + "no sustained impact on another configured device", evidence.deviceId);
                notifyListener(() -> listener.onResetSuppressed(evidence.deviceId));
            }
            return;
        }
        if (resetBudget.tryReset("coordinator")) {
            ladderRung = 2;
            logger.warn(
                    "[coordinator] possible controller-side connection leak affecting {} persists for {}ms after "
                            + "cleanup and another device has been unable to establish for {}ms; adapter reset",
                    evidence.deviceId, evidenceAge, now - adapterWideImpactSince);
            notifyListener(() -> listener.onRecoveryEscalated(2, evidence.deviceId, evidenceAge));
            requestAdapterReset.run();
        }
    }

    private void clearRecoveryEvidence(String reason, long now) {
        ConnectionLeakEvidence evidence = connectionLeakEvidence;
        if (evidence != null) {
            logger.debug("[coordinator] clearing connection-leak evidence for {} generation {} after {}ms: {}",
                    evidence.deviceId, evidence.deviceGeneration, now - evidence.startedAt, reason);
        }
        if (evidence != null) {
            long age = now - evidence.startedAt;
            String deviceId = evidence.deviceId;
            notifyListener(() -> listener.onRecoveryEvidenceCleared(deviceId, reason, age));
        }
        connectionLeakEvidence = null;
        ladderRung = 0;
        adapterWideImpactSince = 0;
        resetSuppressionLogged = false;
    }

    private static final class ConnectionLeakEvidence {
        private final String deviceId;
        private final long deviceGeneration;
        private final long startedAt;
        private final long advertisementSequenceAtFailure;

        private ConnectionLeakEvidence(String deviceId, long deviceGeneration, long startedAt,
                long advertisementSequenceAtFailure) {
            this.deviceId = deviceId;
            this.deviceGeneration = deviceGeneration;
            this.startedAt = startedAt;
            this.advertisementSequenceAtFailure = advertisementSequenceAtFailure;
        }
    }
}
