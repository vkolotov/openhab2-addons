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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.direct_bt.HCIStatusCode;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;

/**
 * Tests for translating actor effects to the existing device port boundary.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
class DevicePortEffectExecutorTest {
    private static final long GENERATION = 41;

    @Test
    void connectEffectMarksConnectingAndReportsSynchronousRejection() {
        FakeDevicePort port = new FakeDevicePort();
        port.connectResult = HCIStatusCode.COMMAND_DISALLOWED;
        List<DeviceEvent> events = new ArrayList<>();
        DevicePortEffectExecutor executor = new DevicePortEffectExecutor(port, events::add);

        boolean handled = executor.execute(new DeviceEffect(GENERATION, ConnectProcedure.EFFECT_CONNECT_LE));

        assertTrue(handled);
        assertEquals(1, port.markConnectingCalls);
        assertEquals(1, port.connectNativeCalls);
        assertEquals(1, events.size());
        DeviceEvent event = events.get(0);
        assertTrue(event instanceof DeviceEvent.ConnectFailed);
        assertEquals(GENERATION, event.generation());
        assertEquals("COMMAND_DISALLOWED", ((DeviceEvent.ConnectFailed) event).reason());
    }

    @Test
    void successfulConnectEffectWaitsForNativeConnectedCallback() {
        FakeDevicePort port = new FakeDevicePort();
        List<DeviceEvent> events = new ArrayList<>();
        DevicePortEffectExecutor executor = new DevicePortEffectExecutor(port, events::add);

        boolean handled = executor.execute(new DeviceEffect(GENERATION, ConnectProcedure.EFFECT_CONNECT_LE));

        assertTrue(handled);
        assertEquals(1, port.markConnectingCalls);
        assertEquals(1, port.connectNativeCalls);
        assertEquals(0, events.size());
    }

    @Test
    void cleanupEffectsMapToPortOperations() {
        FakeDevicePort port = new FakeDevicePort();
        port.prePaired = true;
        DevicePortEffectExecutor executor = new DevicePortEffectExecutor(port, event -> {
        });

        assertTrue(executor.execute(new DeviceEffect(GENERATION, ConnectProcedure.EFFECT_DISCONNECT_NATIVE)));
        assertTrue(executor.execute(new DeviceEffect(GENERATION, ConnectProcedure.EFFECT_CLEAR_STALE_PAIRING)));

        assertEquals(1, port.disconnectNativeCalls);
        assertEquals(1, port.clearStalePairingCalls);
        assertFalse(port.prePaired);
    }

    @Test
    void resolvedGattEffectReportsSuccessOnlyWhenModelIsReady() {
        FakeDevicePort port = new FakeDevicePort();
        List<DeviceEvent> events = new ArrayList<>();
        DevicePortEffectExecutor executor = new DevicePortEffectExecutor(port, events::add);

        assertTrue(executor.execute(new DeviceEffect(GENERATION, ResolveGattProcedure.EFFECT_RESOLVE_GATT)));

        assertEquals(1, port.resolveGattCalls);
        assertEquals(1, events.size());
        DeviceEvent event = events.get(0);
        assertTrue(event instanceof DeviceEvent.GattResolveSucceeded);
        assertEquals(GENERATION, event.generation());
    }

    @Test
    void markConnectedEffectUpdatesPortAndReportsCompletion() {
        FakeDevicePort port = new FakeDevicePort();
        List<DeviceEvent> events = new ArrayList<>();
        DevicePortEffectExecutor executor = new DevicePortEffectExecutor(port, events::add);

        assertTrue(executor.execute(new DeviceEffect(GENERATION, SubscribeNotificationsProcedure.EFFECT_MARK_CONNECTED)));

        assertEquals(1, port.markConnectedCalls);
        assertEquals(1, events.size());
        DeviceEvent event = events.get(0);
        assertTrue(event instanceof DeviceEvent.NativeEffectCompleted);
        assertEquals(GENERATION, event.generation());
        assertEquals(SubscribeNotificationsProcedure.EFFECT_MARK_CONNECTED,
                ((DeviceEvent.NativeEffectCompleted) event).operation());
    }

    @Test
    void unresolvedGattEffectDoesNotInventAFailureVerdict() {
        FakeDevicePort port = new FakeDevicePort();
        port.resolveSucceeds = false;
        List<DeviceEvent> events = new ArrayList<>();
        DevicePortEffectExecutor executor = new DevicePortEffectExecutor(port, events::add);

        assertTrue(executor.execute(new DeviceEffect(GENERATION, ResolveGattProcedure.EFFECT_RESOLVE_GATT)));

        assertEquals(1, port.resolveGattCalls);
        assertEquals(0, events.size());
    }

    @Test
    void unknownEffectIsLeftForAnotherExecutor() {
        FakeDevicePort port = new FakeDevicePort();
        DevicePortEffectExecutor executor = new DevicePortEffectExecutor(port, event -> {
        });

        assertFalse(executor.execute(new DeviceEffect(GENERATION, ConnectProcedure.EFFECT_REQUEST_CONNECT_LEASE)));
    }
}
