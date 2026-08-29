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
package org.openhab.binding.bluetooth.directbt.internal.reconcile.effect;

import java.util.function.Consumer;

import org.direct_bt.HCIStatusCode;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.event.DeviceEffect;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.event.DeviceEvent;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.port.DevicePort;

/**
 * Executes actor effects against the existing {@link DevicePort} abstraction and reports synchronous outcomes back
 * as generation-tagged actor events.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
public final class DevicePortEffectExecutor implements DeviceEffectExecutor {
    private final DevicePort port;
    private final Consumer<DeviceEvent> eventSink;

    public DevicePortEffectExecutor(DevicePort port, Consumer<DeviceEvent> eventSink) {
        this.port = port;
        this.eventSink = eventSink;
    }

    @Override
    public boolean execute(DeviceEffect effect) {
        switch (effect.operation()) {
            case CONNECT_LE:
                executeConnect(effect);
                return true;
            case DISCONNECT_NATIVE:
                port.disconnectNative();
                return true;
            case CLEAR_STALE_PAIRING:
                // Evidence gate (frozen constraint 9): the effect is emitted on every failed/timed-out connect
                // attempt, but keys are only cleared when the device actually holds stored keys — a pre-paired
                // device whose create-connection failed is the dead-bond case; anything else is a no-op.
                if (port.hasStalePairing()) {
                    port.clearStalePairing();
                }
                return true;
            case RESOLVE_GATT:
                executeResolveGatt(effect);
                return true;
            case MARK_CONNECTED:
                port.markConnected();
                eventSink.accept(
                        new DeviceEvent.NativeEffectCompleted(effect.generation(), effect.operation(), "SUCCESS"));
                return true;
            default:
                return false;
        }
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
