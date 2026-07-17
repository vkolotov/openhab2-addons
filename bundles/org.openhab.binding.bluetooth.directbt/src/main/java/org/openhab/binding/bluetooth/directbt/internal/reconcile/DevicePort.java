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

import org.direct_bt.HCIStatusCode;
import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * The narrow set of operations the {@link DeviceReconciler} needs from a Direct-BT device, decoupling the
 * reconcile machinery from the full {@code DirectBTBluetoothDevice}/openHAB device API. All getters return
 * polled native truth; the actions are idempotent corrective commands.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
public interface DevicePort {

    /** @return true iff this device is enabled and the core currently wants a connection held. */
    boolean isWanted();

    /** @return true iff the native device handle is present/usable. */
    boolean hasNativeDevice();

    /** @return native truth: {@code BTDevice.getConnected()}. */
    boolean isNativeConnected();

    /** @return true iff GATT characteristics have been resolved/mapped since the current connection. */
    boolean isGattResolved();

    /** @return true iff a GATT service discovery call is already running for the current connection. */
    boolean isGattResolving();

    /** @return our remembered openHAB connection-state == CONNECTED. */
    boolean isFlagConnected();

    /** @return our remembered openHAB connection-state == CONNECTING. */
    boolean isFlagConnecting();

    /**
     * @return true iff an SMP security negotiation (pairing/encryption) is actively in progress on this device.
     *         When {@code setConnSecurityAuto} is enabled the transport iterates the security ladder, performing
     *         several connect/disconnect cycles to negotiate keys; during that time no stable native link exists
     *         yet the device is making progress. The reconciler must therefore NOT count this window against its
     *         connect deadline (which would tear the negotiation down mid-flight). Always {@code false} for an
     *         unbonded {@code security=NONE} connection, which never negotiates.
     */
    boolean isPairing();

    /**
     * Read-and-clear: whether a native {@code deviceDisconnected} event arrived since the current connect attempt
     * started. The reconciler uses this to clear a pending connect as soon as the attempt is known dead (e.g. a
     * {@code 0x3e} establishment failure) instead of waiting out the full pending deadline; the deadline remains
     * the fallback for silent drops where no event is delivered. {@link #connectNative()} resets it, so an event
     * from a previous attempt can never cancel a new one.
     */
    boolean consumeConnectAttemptFailedEvent();

    /** Drive the openHAB CONNECTED transition + cleanup-free notify (state-flag sync up to native truth). */
    void markConnected();

    /** Drive the openHAB DISCONNECTED transition + release listeners/services (state-flag sync down). */
    void markDisconnected();

    /**
     * Adapter reset already invalidated the native world. Drop this wrapper's model/handle without issuing
     * per-device native disconnect/remove calls; stacking those calls in front of a reset can block the reconcile
     * thread on the same native layer the reset is trying to recover.
     */
    void markDisconnectedByAdapterReset();

    /** Set our flag to CONNECTING (we are about to / are establishing). */
    void markConnecting();

    /**
     * Issue the scan-assisted LE create-connection. Caller (reconciler) has already ensured the adapter scan is
     * OFF. Returns the HCI command acceptance status (SUCCESS = accepted, not yet connected).
     */
    HCIStatusCode connectNative();

    /** Best-effort native disconnect, used to clear a stuck/pending create-connection. */
    void disconnectNative();

    /**
     * @return true iff the device is currently "pre-paired" — i.e. it holds stored SMP keys from an earlier
     *         pairing that a reconnect will try to reuse. When a pre-paired reconnect never establishes, the
     *         stored key is stale (the peer forgot the bond) and must be cleared; see {@link #clearStalePairing}.
     *         Always false for an unbonded {@code security=NONE} device.
     */
    boolean hasStalePairing();

    /**
     * Drop the stored SMP keys for this device so the next connect re-pairs from scratch instead of reusing a
     * (dead) stored key. Best-effort; a no-op when there is nothing to clear.
     */
    void clearStalePairing();

    /**
     * @return true iff the device's configured connection-security requirement is NOT met on the current link —
     *         e.g. an authenticated ("pin") mode was requested but SMP negotiated down to Just-Works/unencrypted
     *         because the peer cannot do MITM. When true the reconciler refuses the connection instead of exposing
     *         GATT over a weaker-than-demanded link (fail closed, never silently downgrade an authenticated mode).
     *         Always false for the "none" / non-authenticated modes, which have nothing stricter to enforce.
     */
    boolean securityRequirementUnmet();

    /** Enumerate + map GATT services/characteristics for the current connection. */
    void resolveGatt();

    /** A stable identifier for logging. */
    String id();
}
