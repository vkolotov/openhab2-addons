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
            port.clearStalePairing();
            return true;
        }
        if (ResolveGattProcedure.EFFECT_RESOLVE_GATT.equals(operation)) {
            executeResolveGatt(effect);
            return true;
        }
        return false;
    }

    private void executeConnect(DeviceEffect effect) {
        port.markConnecting();
        HCIStatusCode result = port.connectNative();
        if (result != HCIStatusCode.SUCCESS) {
            eventSink.accept(new DeviceEvent.ConnectFailed(effect.generation(), result.name(), false));
        }
    }

    private void executeResolveGatt(DeviceEffect effect) {
        port.resolveGatt();
        if (port.isGattResolved()) {
            eventSink.accept(new DeviceEvent.GattResolveSucceeded(effect.generation()));
        }
    }
}
