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
 * Pure post-connect settling procedure. It deliberately separates native-connected from ATT/GATT use.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
final class SettleLinkProcedure implements DeviceProcedure {
    static final String EFFECT_SCHEDULE_LINK_SETTLE_TIMER = "scheduleLinkSettleTimer";
    static final String EFFECT_START_RESOLVE_GATT_PROCEDURE = "startProcedure:RESOLVE_GATT";
    static final String EFFECT_DISCONNECT_NATIVE = ConnectProcedure.EFFECT_DISCONNECT_NATIVE;

    private final long maxResidencyMs;

    SettleLinkProcedure(long maxResidencyMs) {
        this.maxResidencyMs = maxResidencyMs;
    }

    @Override
    public DeviceProcedureName name() {
        return DeviceProcedureName.SETTLE_LINK;
    }

    @Override
    public DeviceActorState actorState() {
        return DeviceActorState.LINK_SETTLING;
    }

    @Override
    public DeviceWaitingOn waitingOn() {
        return DeviceWaitingOn.SETTLE_TIMER;
    }

    @Override
    public long maxResidencyMs() {
        return maxResidencyMs;
    }

    @Override
    public void start(DeviceProcedureContext ctx) {
        ctx.emit(new DeviceEffect(ctx.generation(), EFFECT_SCHEDULE_LINK_SETTLE_TIMER));
    }

    @Override
    public void onEvent(DeviceEvent event, DeviceProcedureContext ctx) {
        if (event instanceof DeviceEvent.LinkSettleTimerExpired) {
            ctx.emit(new DeviceEffect(ctx.generation(), EFFECT_START_RESOLVE_GATT_PROCEDURE));
            ctx.transitionTo(DeviceActorState.RESOLVING_GATT, DeviceWaitingOn.GATT_RESOLVE, event.kind());
            return;
        }
        if (event instanceof DeviceEvent.ProcedureDeadlineExpired) {
            DeviceEvent.ProcedureDeadlineExpired deadline = (DeviceEvent.ProcedureDeadlineExpired) event;
            if (deadline.procedure() == DeviceProcedureName.SETTLE_LINK) {
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
