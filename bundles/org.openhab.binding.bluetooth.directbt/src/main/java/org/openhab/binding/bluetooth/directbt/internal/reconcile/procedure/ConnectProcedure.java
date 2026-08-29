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

import static org.openhab.binding.bluetooth.directbt.internal.reconcile.event.DeviceEffectOperation.CLEAR_STALE_PAIRING;
import static org.openhab.binding.bluetooth.directbt.internal.reconcile.event.DeviceEffectOperation.CONNECT_LE;
import static org.openhab.binding.bluetooth.directbt.internal.reconcile.event.DeviceEffectOperation.DISCONNECT_NATIVE;
import static org.openhab.binding.bluetooth.directbt.internal.reconcile.event.DeviceEffectOperation.REQUEST_CONNECT_LEASE;
import static org.openhab.binding.bluetooth.directbt.internal.reconcile.event.DeviceEffectOperation.START_SETTLE_LINK_PROCEDURE;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.device.DeviceActorState;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.device.DeviceWaitingOn;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.event.DeviceEffect;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.event.DeviceEvent;

/**
 * Pure connect-path procedure. It models the control-plane decisions and emits effects; native Direct-BT work remains
 * outside the actor and reports back as generation-tagged events.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
public final class ConnectProcedure implements DeviceProcedure {
    /**
     * Residency bound for the CONNECT_LEASE wait. A lease legitimately takes as long as the adapter
     * coordinator's discovery slice (30 s) before the scan yields; this bound only catches a wedged
     * scan that never stops (no lease may outwait it silently ).
     */
    public static final long LEASE_WAIT_DEADLINE_MS = 45_000;

    private final long maxResidencyMs;

    public ConnectProcedure(long maxResidencyMs) {
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
    public long maxResidencyMs(DeviceWaitingOn waitingOn) {
        return waitingOn == DeviceWaitingOn.CONNECT_LEASE ? LEASE_WAIT_DEADLINE_MS : maxResidencyMs;
    }

    @Override
    public void start(DeviceProcedureContext ctx) {
        ctx.emit(new DeviceEffect(ctx.generation(), REQUEST_CONNECT_LEASE));
    }

    @Override
    public void onEvent(DeviceEvent event, DeviceProcedureContext ctx) {
        if (event instanceof DeviceEvent.ConnectLeaseGranted) {
            ctx.transitionTo(DeviceActorState.CONNECTING, DeviceWaitingOn.NATIVE_CONNECT, event.kind());
            ctx.emit(new DeviceEffect(ctx.generation(), CONNECT_LE));
            return;
        }
        if (event instanceof DeviceEvent.NativeConnected) {
            ctx.emit(new DeviceEffect(ctx.generation(), START_SETTLE_LINK_PROCEDURE));
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
                ctx.emit(new DeviceEffect(ctx.generation(), DISCONNECT_NATIVE));
                // A create-connection that silently never establishes is the dead-bond signature on a
                // pre-paired device (the stored key blocks the encrypted reconnect); the effect executor
                // gates the actual clear on that evidence, so this is a no-op for unpaired devices.
                ctx.emit(new DeviceEffect(ctx.generation(), CLEAR_STALE_PAIRING));
                ctx.transitionTo(DeviceActorState.BACKING_OFF, DeviceWaitingOn.BACKOFF_TIMER, event.kind());
            }
        }
    }

    @Override
    public void cancel(String reason, DeviceProcedureContext ctx) {
        ctx.emit(new DeviceEffect(ctx.generation(), DISCONNECT_NATIVE));
    }

    private void handleConnectFailure(DeviceEvent.ConnectFailed event, DeviceProcedureContext ctx) {
        ctx.emit(new DeviceEffect(ctx.generation(), DISCONNECT_NATIVE));
        if (event.staleBondSuspected()) {
            ctx.emit(new DeviceEffect(ctx.generation(), CLEAR_STALE_PAIRING));
        }
        ctx.transitionTo(DeviceActorState.BACKING_OFF, DeviceWaitingOn.BACKOFF_TIMER,
                event.kind() + ":" + event.reason());
    }
}
