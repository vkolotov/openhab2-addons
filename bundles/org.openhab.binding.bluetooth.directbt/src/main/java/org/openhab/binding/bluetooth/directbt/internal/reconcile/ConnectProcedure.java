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
 * Pure connect-path procedure. It models the control-plane decisions and emits effects; native Direct-BT work remains
 * outside the actor and reports back as generation-tagged events.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
final class ConnectProcedure implements DeviceProcedure {
    static final String EFFECT_REQUEST_CONNECT_LEASE = "requestConnectLease";
    static final String EFFECT_CONNECT_LE = "connectLE";
    static final String EFFECT_START_SETTLE_LINK_PROCEDURE = "startProcedure:SETTLE_LINK";
    static final String EFFECT_DISCONNECT_NATIVE = "disconnectNative";
    static final String EFFECT_CLEAR_STALE_PAIRING = "clearStalePairing";

    private final long maxResidencyMs;

    ConnectProcedure(long maxResidencyMs) {
        this.maxResidencyMs = maxResidencyMs;
    }

    @Override
    public DeviceProcedureName name() {
        return DeviceProcedureName.CONNECT;
    }

    @Override
    public DeviceActorState actorState() {
        return DeviceActorState.CONNECTING;
    }

    @Override
    public DeviceWaitingOn waitingOn() {
        return DeviceWaitingOn.CONNECT_LEASE;
    }

    @Override
    public long maxResidencyMs() {
        return maxResidencyMs;
    }

    @Override
    public void start(DeviceProcedureContext ctx) {
        ctx.emit(new DeviceEffect(ctx.generation(), EFFECT_REQUEST_CONNECT_LEASE));
    }

    @Override
    public void onEvent(DeviceEvent event, DeviceProcedureContext ctx) {
        if (event instanceof DeviceEvent.ConnectLeaseGranted) {
            ctx.transitionTo(DeviceActorState.CONNECTING, DeviceWaitingOn.NATIVE_CONNECT, event.kind());
            ctx.emit(new DeviceEffect(ctx.generation(), EFFECT_CONNECT_LE));
            return;
        }
        if (event instanceof DeviceEvent.NativeConnected) {
            ctx.emit(new DeviceEffect(ctx.generation(), EFFECT_START_SETTLE_LINK_PROCEDURE));
            ctx.transitionTo(DeviceActorState.LINK_SETTLING, DeviceWaitingOn.SETTLE_TIMER, event.kind());
            return;
        }
        if (event instanceof DeviceEvent.PairingStarted) {
            ctx.transitionTo(DeviceActorState.CONNECTING, DeviceWaitingOn.PAIRING, event.kind());
            return;
        }
        if (event instanceof DeviceEvent.PairingEnded) {
            ctx.transitionTo(DeviceActorState.CONNECTING, DeviceWaitingOn.NATIVE_CONNECT, event.kind());
            return;
        }
        if (event instanceof DeviceEvent.ConnectFailed) {
            handleConnectFailure((DeviceEvent.ConnectFailed) event, ctx);
            return;
        }
        if (event instanceof DeviceEvent.ProcedureDeadlineExpired) {
            DeviceEvent.ProcedureDeadlineExpired deadline = (DeviceEvent.ProcedureDeadlineExpired) event;
            if (deadline.procedure() == DeviceProcedureName.CONNECT) {
                ctx.emit(new DeviceEffect(ctx.generation(), EFFECT_DISCONNECT_NATIVE));
                // A create-connection that silently never establishes is the dead-bond signature on a
                // pre-paired device (the stored key blocks the encrypted reconnect); the effect executor
                // gates the actual clear on that evidence, so this is a no-op for unpaired devices.
                ctx.emit(new DeviceEffect(ctx.generation(), EFFECT_CLEAR_STALE_PAIRING));
                ctx.transitionTo(DeviceActorState.BACKING_OFF, DeviceWaitingOn.BACKOFF_TIMER, event.kind());
            }
        }
    }

    @Override
    public void cancel(String reason, DeviceProcedureContext ctx) {
        ctx.emit(new DeviceEffect(ctx.generation(), EFFECT_DISCONNECT_NATIVE));
    }

    private void handleConnectFailure(DeviceEvent.ConnectFailed event, DeviceProcedureContext ctx) {
        ctx.emit(new DeviceEffect(ctx.generation(), EFFECT_DISCONNECT_NATIVE));
        if (event.staleBondSuspected()) {
            ctx.emit(new DeviceEffect(ctx.generation(), EFFECT_CLEAR_STALE_PAIRING));
        }
        ctx.transitionTo(DeviceActorState.BACKING_OFF, DeviceWaitingOn.BACKOFF_TIMER,
                event.kind() + ":" + event.reason());
    }
}
