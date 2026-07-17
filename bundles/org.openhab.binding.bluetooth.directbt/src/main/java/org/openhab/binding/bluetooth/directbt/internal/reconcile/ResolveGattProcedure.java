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
 * Pure GATT discovery procedure. GATT resolution starts only after the link-settle procedure hands off to it.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
final class ResolveGattProcedure implements DeviceProcedure {
    static final String EFFECT_RESOLVE_GATT = "resolveGatt";
    static final String EFFECT_START_SUBSCRIBE_PROCEDURE = "startProcedure:SUBSCRIBE_NOTIFICATIONS";
    static final String EFFECT_DISCONNECT_NATIVE = ConnectProcedure.EFFECT_DISCONNECT_NATIVE;

    private final long maxResidencyMs;

    ResolveGattProcedure(long maxResidencyMs) {
        this.maxResidencyMs = maxResidencyMs;
    }

    @Override
    public DeviceProcedureName name() {
        return DeviceProcedureName.RESOLVE_GATT;
    }

    @Override
    public DeviceActorState actorState() {
        return DeviceActorState.RESOLVING_GATT;
    }

    @Override
    public DeviceWaitingOn waitingOn() {
        return DeviceWaitingOn.GATT_RESOLVE;
    }

    @Override
    public long maxResidencyMs() {
        return maxResidencyMs;
    }

    @Override
    public void start(DeviceProcedureContext ctx) {
        ctx.emit(new DeviceEffect(ctx.generation(), EFFECT_RESOLVE_GATT));
    }

    @Override
    public void onEvent(DeviceEvent event, DeviceProcedureContext ctx) {
        if (event instanceof DeviceEvent.GattResolveRequested) {
            // Retry pacing is external (one request per reconcile tick); the procedure just re-issues the work.
            ctx.emit(new DeviceEffect(ctx.generation(), EFFECT_RESOLVE_GATT));
            return;
        }
        if (event instanceof DeviceEvent.GattResolveSucceeded) {
            ctx.emit(new DeviceEffect(ctx.generation(), EFFECT_START_SUBSCRIBE_PROCEDURE));
            ctx.transitionTo(DeviceActorState.SUBSCRIBING, DeviceWaitingOn.SUBSCRIPTION, event.kind());
            return;
        }
        if (event instanceof DeviceEvent.GattResolveFailed) {
            DeviceEvent.GattResolveFailed failed = (DeviceEvent.GattResolveFailed) event;
            ctx.emit(new DeviceEffect(ctx.generation(), EFFECT_DISCONNECT_NATIVE));
            ctx.transitionTo(DeviceActorState.BACKING_OFF, DeviceWaitingOn.BACKOFF_TIMER,
                    event.kind() + ":" + failed.reason());
            return;
        }
        if (event instanceof DeviceEvent.ProcedureDeadlineExpired) {
            DeviceEvent.ProcedureDeadlineExpired deadline = (DeviceEvent.ProcedureDeadlineExpired) event;
            if (deadline.procedure() == DeviceProcedureName.RESOLVE_GATT) {
                ctx.emit(new DeviceEffect(ctx.generation(), EFFECT_DISCONNECT_NATIVE));
                ctx.transitionTo(DeviceActorState.BACKING_OFF, DeviceWaitingOn.BACKOFF_TIMER, event.kind());
            }
        }
    }

    @Override
    public void cancel(String reason, DeviceProcedureContext ctx) {
        ctx.emit(new DeviceEffect(ctx.generation(), EFFECT_DISCONNECT_NATIVE));
    }
}
