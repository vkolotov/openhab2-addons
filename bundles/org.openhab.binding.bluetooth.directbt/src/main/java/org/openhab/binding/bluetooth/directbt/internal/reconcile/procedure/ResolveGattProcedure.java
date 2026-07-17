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
import static org.openhab.binding.bluetooth.directbt.internal.reconcile.event.DeviceEffectOperation.RESOLVE_GATT;
import static org.openhab.binding.bluetooth.directbt.internal.reconcile.event.DeviceEffectOperation.START_SUBSCRIBE_PROCEDURE;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.device.DeviceActorState;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.device.DeviceWaitingOn;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.event.DeviceEffect;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.event.DeviceEvent;

/**
 * Pure GATT discovery procedure. GATT resolution starts only after the link-settle procedure hands off to it.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
public final class ResolveGattProcedure implements DeviceProcedure {
    private final long maxResidencyMs;

    public ResolveGattProcedure(long maxResidencyMs) {
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
        ctx.emit(new DeviceEffect(ctx.generation(), RESOLVE_GATT));
    }

    @Override
    public void onEvent(DeviceEvent event, DeviceProcedureContext ctx) {
        if (event instanceof DeviceEvent.GattResolveRequested) {
            // Retry pacing is external (one request per reconcile tick); the procedure just re-issues the work.
            ctx.emit(new DeviceEffect(ctx.generation(), RESOLVE_GATT));
            return;
        }
        if (event instanceof DeviceEvent.GattResolveSucceeded) {
            ctx.emit(new DeviceEffect(ctx.generation(), START_SUBSCRIBE_PROCEDURE));
            ctx.transitionTo(DeviceActorState.SUBSCRIBING, DeviceWaitingOn.SUBSCRIPTION, event.kind());
            return;
        }
        if (event instanceof DeviceEvent.GattResolveFailed) {
            DeviceEvent.GattResolveFailed failed = (DeviceEvent.GattResolveFailed) event;
            ctx.emit(new DeviceEffect(ctx.generation(), DISCONNECT_NATIVE));
            ctx.transitionTo(DeviceActorState.BACKING_OFF, DeviceWaitingOn.BACKOFF_TIMER,
                    event.kind() + ":" + failed.reason());
            return;
        }
        if (event instanceof DeviceEvent.ProcedureDeadlineExpired) {
            DeviceEvent.ProcedureDeadlineExpired deadline = (DeviceEvent.ProcedureDeadlineExpired) event;
            if (deadline.procedure() == DeviceProcedureName.RESOLVE_GATT) {
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
