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

import java.util.function.Consumer;

import org.direct_bt.HCIStatusCode;
import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Executes actor effects against the existing {@link DevicePort} abstraction and reports synchronous outcomes back
 * as generation-tagged actor events.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
final class DevicePortEffectExecutor implements DeviceEffectExecutor {
    private final DevicePort port;
    private final Consumer<DeviceEvent> eventSink;

    DevicePortEffectExecutor(DevicePort port, Consumer<DeviceEvent> eventSink) {
        this.port = port;
        this.eventSink = eventSink;
    }

    @Override
    public boolean execute(DeviceEffect effect) {
        String operation = effect.operation();
        if (ConnectProcedure.EFFECT_CONNECT_LE.equals(operation)) {
            executeConnect(effect);
            return true;
        }
        if (ConnectProcedure.EFFECT_DISCONNECT_NATIVE.equals(operation)) {
            port.disconnectNative();
            return true;
        }
        if (ConnectProcedure.EFFECT_CLEAR_STALE_PAIRING.equals(operation)) {
            // Evidence gate (frozen constraint 9): the effect is emitted on every failed/timed-out connect
            // attempt, but keys are only cleared when the device actually holds stored keys — a pre-paired
            // device whose create-connection failed is the dead-bond case; anything else is a no-op.
            if (port.hasStalePairing()) {
                port.clearStalePairing();
            }
            return true;
        }
        if (ResolveGattProcedure.EFFECT_RESOLVE_GATT.equals(operation)) {
            executeResolveGatt(effect);
            return true;
        }
        if (SubscribeNotificationsProcedure.EFFECT_MARK_CONNECTED.equals(operation)) {
            port.markConnected();
            eventSink.accept(new DeviceEvent.NativeEffectCompleted(effect.generation(), operation, "SUCCESS"));
            return true;
        }
        return false;
    }

    private void executeConnect(DeviceEffect effect) {
        port.markConnecting();
        HCIStatusCode result = port.connectNative();
        if (result != HCIStatusCode.SUCCESS) {
            // A failed attempt on a pre-paired device is stale-bond evidence (the stored key is what a
            // reconnect would reuse, so it is the prime suspect); the procedure routes it to the clear effect.
            eventSink.accept(new DeviceEvent.ConnectFailed(effect.generation(), result.name(), port.hasStalePairing()));
        }
    }

    private void executeResolveGatt(DeviceEffect effect) {
        // Never issue a discovery on top of one already in flight (the 12:00 race lesson: an in-flight
        // walk is progress and must not be disturbed); the resolve procedure's deadline bounds a hang.
        if (!port.isGattResolving()) {
            port.resolveGatt();
        }
        if (port.isGattResolved()) {
            eventSink.accept(new DeviceEvent.GattResolveSucceeded(effect.generation()));
        }
    }
}
