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

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.event.DeviceEffect;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.event.DeviceEffectOperation;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.event.DeviceEvent;

/**
 * Handles the actor's scan-off connect lease request. If the scan is still on, the lease request is remembered and
 * granted by a later tick once the adapter is observed scan-off.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
public final class ConnectLeaseEffectExecutor implements DeviceEffectExecutor {
    private final BooleanSupplier scanIsOff;
    private final Consumer<DeviceEvent> eventSink;

    private long pendingGeneration = -1;

    public ConnectLeaseEffectExecutor(BooleanSupplier scanIsOff, Consumer<DeviceEvent> eventSink) {
        this.scanIsOff = scanIsOff;
        this.eventSink = eventSink;
    }

    @Override
    public boolean execute(DeviceEffect effect) {
        if (effect.operation() != DeviceEffectOperation.REQUEST_CONNECT_LEASE) {
            return false;
        }
        pendingGeneration = effect.generation();
        tick(effect.generation());
        return true;
    }

    public void tick(long currentGeneration) {
        if (pendingGeneration == -1) {
            return;
        }
        if (pendingGeneration != currentGeneration) {
            pendingGeneration = -1;
            return;
        }
        if (scanIsOff.getAsBoolean()) {
            long generation = pendingGeneration;
            pendingGeneration = -1;
            eventSink.accept(new DeviceEvent.ConnectLeaseGranted(generation));
        }
    }

    public boolean hasPendingLease() {
        return pendingGeneration != -1;
    }
}
