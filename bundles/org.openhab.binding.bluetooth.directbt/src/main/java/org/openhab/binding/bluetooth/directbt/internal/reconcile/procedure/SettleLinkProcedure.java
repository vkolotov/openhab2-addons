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
package org.openhab.binding.bluetooth.directbt.internal.reconcile.procedure;

import static org.openhab.binding.bluetooth.directbt.internal.reconcile.event.DeviceEffectOperation.DISCONNECT_NATIVE;
import static org.openhab.binding.bluetooth.directbt.internal.reconcile.event.DeviceEffectOperation.SCHEDULE_LINK_SETTLE_TIMER;
import static org.openhab.binding.bluetooth.directbt.internal.reconcile.event.DeviceEffectOperation.START_RESOLVE_GATT_PROCEDURE;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.device.DeviceActorState;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.device.DeviceWaitingOn;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.event.DeviceEffect;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.event.DeviceEvent;

/**
 * Pure post-connect settling procedure. It deliberately separates native-connected from ATT/GATT use.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
public final class SettleLinkProcedure implements DeviceProcedure {
    private final long maxResidencyMs;

    public SettleLinkProcedure(long maxResidencyMs) {
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
        ctx.emit(new DeviceEffect(ctx.generation(), SCHEDULE_LINK_SETTLE_TIMER));
    }

    @Override
    public void onEvent(DeviceEvent event, DeviceProcedureContext ctx) {
        if (event instanceof DeviceEvent.LinkSettleTimerExpired) {
            ctx.emit(new DeviceEffect(ctx.generation(), START_RESOLVE_GATT_PROCEDURE));
            ctx.transitionTo(DeviceActorState.RESOLVING_GATT, DeviceWaitingOn.GATT_RESOLVE, event.kind());
            return;
        }
        if (event instanceof DeviceEvent.ProcedureDeadlineExpired) {
            DeviceEvent.ProcedureDeadlineExpired deadline = (DeviceEvent.ProcedureDeadlineExpired) event;
            if (deadline.procedure() == DeviceProcedureName.SETTLE_LINK) {
                ctx.emit(new DeviceEffect(ctx.generation(), DISCONNECT_NATIVE));
                ctx.transitionTo(DeviceActorState.BACKING_OFF, DeviceWaitingOn.BACKOFF_TIMER, event.kind());
            }
        }
    }

    @Override
    public void cancel(String reason, DeviceProcedureContext ctx) {
        ctx.emit(new DeviceEffect(ctx.generation(), DISCONNECT_NATIVE));
    }
}
