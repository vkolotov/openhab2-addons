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

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Stable online procedure. It has no deadline and exists so an explicit wanted-offline cancellation still maps to
 * the native disconnect cleanup effect.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
final class OnlineMonitorProcedure implements DeviceProcedure {
    static final String EFFECT_DISCONNECT_NATIVE = ConnectProcedure.EFFECT_DISCONNECT_NATIVE;

    @Override
    public DeviceProcedureName name() {
        return DeviceProcedureName.ONLINE_MONITOR;
    }

    @Override
    public DeviceActorState actorState() {
        return DeviceActorState.ONLINE;
    }

    @Override
    public DeviceWaitingOn waitingOn() {
        return DeviceWaitingOn.NOTHING;
    }

    @Override
    public long maxResidencyMs() {
        return 0;
    }

    @Override
    public void start(DeviceProcedureContext ctx) {
    }

    @Override
    public void onEvent(DeviceEvent event, DeviceProcedureContext ctx) {
    }

    @Override
    public void cancel(String reason, DeviceProcedureContext ctx) {
        ctx.emit(new DeviceEffect(ctx.generation(), EFFECT_DISCONNECT_NATIVE));
    }
}
