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

    /** @return our remembered openHAB connection-state == CONNECTED. */
    boolean isFlagConnected();

    /** @return our remembered openHAB connection-state == CONNECTING. */
    boolean isFlagConnecting();

    /** Drive the openHAB CONNECTED transition + cleanup-free notify (state-flag sync up to native truth). */
    void markConnected();

    /** Drive the openHAB DISCONNECTED transition + release listeners/services (state-flag sync down). */
    void markDisconnected();

    /** Set our flag to CONNECTING (we are about to / are establishing). */
    void markConnecting();

    /**
     * Issue the scan-assisted LE create-connection. Caller (reconciler) has already ensured the adapter scan is
     * OFF. Returns the HCI command acceptance status (SUCCESS = accepted, not yet connected).
     */
    HCIStatusCode connectNative();

    /** Best-effort native disconnect, used to clear a stuck/pending create-connection. */
    void disconnectNative();

    /** Enumerate + map GATT services/characteristics for the current connection. */
    void resolveGatt();

    /** A stable identifier for logging. */
    String id();
}
