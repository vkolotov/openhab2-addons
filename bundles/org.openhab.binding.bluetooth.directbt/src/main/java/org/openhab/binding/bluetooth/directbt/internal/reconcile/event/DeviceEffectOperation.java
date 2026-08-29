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
package org.openhab.binding.bluetooth.directbt.internal.reconcile.event;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.procedure.DeviceProcedureName;

/**
 * Closed set of side effects emitted by device procedures.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
public enum DeviceEffectOperation {
    REQUEST_CONNECT_LEASE,
    CONNECT_LE,
    START_SETTLE_LINK_PROCEDURE(DeviceProcedureName.SETTLE_LINK),
    DISCONNECT_NATIVE,
    CLEAR_STALE_PAIRING,
    SCHEDULE_LINK_SETTLE_TIMER,
    START_RESOLVE_GATT_PROCEDURE(DeviceProcedureName.RESOLVE_GATT),
    RESOLVE_GATT,
    START_SUBSCRIBE_PROCEDURE(DeviceProcedureName.SUBSCRIBE_NOTIFICATIONS),
    MARK_CONNECTED,
    START_ONLINE_MONITOR_PROCEDURE(DeviceProcedureName.ONLINE_MONITOR);

    private final @Nullable DeviceProcedureName targetProcedure;

    DeviceEffectOperation() {
        this(null);
    }

    DeviceEffectOperation(@Nullable DeviceProcedureName targetProcedure) {
        this.targetProcedure = targetProcedure;
    }

    public @Nullable DeviceProcedureName targetProcedure() {
        return targetProcedure;
    }
}
