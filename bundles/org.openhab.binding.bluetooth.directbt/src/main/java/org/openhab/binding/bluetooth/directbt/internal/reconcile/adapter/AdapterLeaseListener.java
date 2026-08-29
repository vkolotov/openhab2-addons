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

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Observes the adapter's radio-arbitration and recovery decisions. Purely passive: implementations must not
 * influence arbitration, must not throw, and must not block — they run inline on the bridge reconcile tick.
 * <p>
 * The adapter owns a single radio that discovery and connection establishment compete for, so its decisions are
 * only visible here. Per-device metrics can show that a device waited; only these can show <em>why</em> — that
 * another device held the radio, and for how long.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
public interface AdapterLeaseListener {

    /** A listener that records nothing, so callers never need a null check. */
    AdapterLeaseListener NOOP = new AdapterLeaseListener() {
    };

    /**
     * The per-tick scan decision.
     *
     * @param scanning whether the radio will scan this tick
     * @param contended whether discovery and establishment were both demanding the radio
     */
    default void onScanDecision(boolean scanning, boolean contended) {
    }

    /**
     * The contention time-slice flipped owner. The ratio of connect to discovery slice time is what says whether
     * {@code CONNECT_SLICE_MS}/{@code DISCOVERY_SLICE_MS} are fairly tuned for this fleet.
     *
     * @param discoverySlice true if discovery now owns the radio, false if establishment does
     * @param previousSliceMs how long the outgoing slice actually held the radio
     */
    default void onSliceSwitched(boolean discoverySlice, long previousSliceMs) {
    }

    /**
     * Connection-leak evidence was armed by an ambiguous {@code 0x3e} failure on a recently advertising device.
     * This is the first rung of the evidence-gated recovery ladder, not yet an action.
     */
    default void onRecoveryEvidenceArmed(String deviceId) {
    }

    /** Evidence was dropped without escalating — the healthy outcome. */
    default void onRecoveryEvidenceCleared(String deviceId, String reason, long evidenceAgeMs) {
    }

    /** The ladder escalated: rung 1 is a targeted cleanup sweep, rung 2 an adapter reset. */
    default void onRecoveryEscalated(int rung, String deviceId, long evidenceAgeMs) {
    }

    /**
     * An adapter reset was withheld because no second device was sustainedly affected. Counting suppressions
     * proves the evidence gate is doing its job rather than silently never arming.
     */
    default void onResetSuppressed(String deviceId) {
    }
}
