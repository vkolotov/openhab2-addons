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
 * Final connection-ready procedure. Today this maps the actor's subscription phase onto the existing
 * {@link DevicePort#markConnected()} boundary; real per-characteristic subscription policy can move behind this
 * procedure later without changing the surrounding lifecycle.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
final class SubscribeNotificationsProcedure implements DeviceProcedure {
    static final String EFFECT_MARK_CONNECTED = "markConnected";
    static final String EFFECT_START_ONLINE_MONITOR_PROCEDURE = "startProcedure:ONLINE_MONITOR";
    static final String EFFECT_DISCONNECT_NATIVE = ConnectProcedure.EFFECT_DISCONNECT_NATIVE;

    private final long maxResidencyMs;

    SubscribeNotificationsProcedure(long maxResidencyMs) {
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
        ctx.emit(new DeviceEffect(ctx.generation(), EFFECT_MARK_CONNECTED));
    }

    @Override
    public void onEvent(DeviceEvent event, DeviceProcedureContext ctx) {
        if (event instanceof DeviceEvent.NativeEffectCompleted) {
            DeviceEvent.NativeEffectCompleted completed = (DeviceEvent.NativeEffectCompleted) event;
            if (EFFECT_MARK_CONNECTED.equals(completed.operation())) {
                ctx.emit(new DeviceEffect(ctx.generation(), EFFECT_START_ONLINE_MONITOR_PROCEDURE));
            }
            return;
        }
        if (event instanceof DeviceEvent.ProcedureDeadlineExpired) {
            DeviceEvent.ProcedureDeadlineExpired deadline = (DeviceEvent.ProcedureDeadlineExpired) event;
            if (deadline.procedure() == DeviceProcedureName.SUBSCRIBE_NOTIFICATIONS) {
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
