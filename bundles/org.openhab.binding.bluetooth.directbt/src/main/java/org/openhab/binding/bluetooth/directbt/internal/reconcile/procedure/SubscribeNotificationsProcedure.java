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
import static org.openhab.binding.bluetooth.directbt.internal.reconcile.event.DeviceEffectOperation.MARK_CONNECTED;
import static org.openhab.binding.bluetooth.directbt.internal.reconcile.event.DeviceEffectOperation.START_ONLINE_MONITOR_PROCEDURE;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.device.DeviceActorState;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.device.DeviceWaitingOn;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.event.DeviceEffect;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.event.DeviceEvent;

/**
 * Final connection-ready procedure. Today this maps the actor's subscription phase onto the existing
 * {@link DevicePort#markConnected()} boundary; real per-characteristic subscription policy can move behind this
 * procedure later without changing the surrounding lifecycle.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
public final class SubscribeNotificationsProcedure implements DeviceProcedure {
    private final long maxResidencyMs;

    public SubscribeNotificationsProcedure(long maxResidencyMs) {
        this.maxResidencyMs = maxResidencyMs;
    }

    @Override
    public DeviceProcedureName name() {
        return DeviceProcedureName.SUBSCRIBE_NOTIFICATIONS;
    }

    @Override
    public DeviceActorState actorState() {
        return DeviceActorState.SUBSCRIBING;
    }

    @Override
    public DeviceWaitingOn waitingOn() {
        return DeviceWaitingOn.SUBSCRIPTION;
    }

    @Override
    public long maxResidencyMs() {
        return maxResidencyMs;
    }

    @Override
    public void start(DeviceProcedureContext ctx) {
        ctx.emit(new DeviceEffect(ctx.generation(), MARK_CONNECTED));
    }

    @Override
    public void onEvent(DeviceEvent event, DeviceProcedureContext ctx) {
        if (event instanceof DeviceEvent.NativeEffectCompleted) {
            DeviceEvent.NativeEffectCompleted completed = (DeviceEvent.NativeEffectCompleted) event;
            if (completed.operation() == MARK_CONNECTED) {
                ctx.emit(new DeviceEffect(ctx.generation(), START_ONLINE_MONITOR_PROCEDURE));
            }
            return;
        }
        if (event instanceof DeviceEvent.ProcedureDeadlineExpired) {
            DeviceEvent.ProcedureDeadlineExpired deadline = (DeviceEvent.ProcedureDeadlineExpired) event;
            if (deadline.procedure() == DeviceProcedureName.SUBSCRIBE_NOTIFICATIONS) {
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
