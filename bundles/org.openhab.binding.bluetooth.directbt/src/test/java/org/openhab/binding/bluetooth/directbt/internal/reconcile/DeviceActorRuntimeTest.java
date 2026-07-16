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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.direct_bt.HCIStatusCode;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Tests for the composed actor/effect runtime.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
class DeviceActorRuntimeTest {
    private static final long START = 1_784_200_000_000L;

    @Test
    void connectLeaseWaitsForScanOffBeforeRunningPortConnect() {
        MutableClock clock = new MutableClock(START);
        DeviceActor actor = new DeviceActor("test-device", ReconcileTestSupport.logger(), clock);
        FakeDevicePort port = new FakeDevicePort();
        AtomicBoolean scanOff = new AtomicBoolean(false);
        DeviceActorRuntime runtime = new DeviceActorRuntime(actor, DeviceActorRuntimeTest::createProcedure,
                scanOff::get, port);

        runtime.start(new ConnectProcedure(30_000), "test-connect");

        assertEquals(0, port.connectNativeCalls);
        assertEquals(DeviceWaitingOn.CONNECT_LEASE, runtime.diagnostics().waitingOn());

        scanOff.set(true);
        runtime.tick();

        assertEquals(1, port.markConnectingCalls);
        assertEquals(1, port.connectNativeCalls);
        assertEquals(DeviceWaitingOn.NATIVE_CONNECT, runtime.diagnostics().waitingOn());
    }

    @Test
    void connectRejectionFlowsThroughObserverAndCleanupEffect() {
        MutableClock clock = new MutableClock(START);
        DeviceActor actor = new DeviceActor("test-device", ReconcileTestSupport.logger(), clock);
        FakeDevicePort port = new FakeDevicePort();
        port.connectResult = HCIStatusCode.COMMAND_DISALLOWED;
        List<DeviceEvent> observedEvents = new ArrayList<>();
        DeviceActorRuntime runtime = new DeviceActorRuntime(actor, DeviceActorRuntimeTest::createProcedure,
                () -> true, port, observedEvents::add);

        runtime.start(new ConnectProcedure(30_000), "test-connect");

        assertEquals(1, port.connectNativeCalls);
        assertEquals(1, port.disconnectNativeCalls);
        assertEquals(DeviceActorState.BACKING_OFF, runtime.diagnostics().state());
        assertEquals(2, observedEvents.size());
        assertTrue(observedEvents.get(0) instanceof DeviceEvent.ConnectLeaseGranted);
        DeviceEvent event = observedEvents.get(1);
        assertTrue(event instanceof DeviceEvent.ConnectFailed);
        assertEquals("COMMAND_DISALLOWED", ((DeviceEvent.ConnectFailed) event).reason());
    }

    @Test
    void gattResolveSuccessHandsOffToUnhandledSubscribeEffect() {
        MutableClock clock = new MutableClock(START);
        DeviceActor actor = new DeviceActor("test-device", ReconcileTestSupport.logger(), clock);
        FakeDevicePort port = new FakeDevicePort();
        DeviceActorRuntime runtime = new DeviceActorRuntime(actor, DeviceActorRuntimeTest::createProcedure,
                () -> true, port);

        runtime.start(new ResolveGattProcedure(120_000), "test-gatt");

        assertEquals(1, port.resolveGattCalls);
        assertEquals(DeviceActorState.SUBSCRIBING, runtime.diagnostics().state());
        assertEffects(runtime.drainUnhandledEffects(), ResolveGattProcedure.EFFECT_START_SUBSCRIBE_PROCEDURE);
    }

    @Test
    void settleTimerEffectRemainsUnhandledForSchedulerOwner() {
        MutableClock clock = new MutableClock(START);
        DeviceActor actor = new DeviceActor("test-device", ReconcileTestSupport.logger(), clock);
        FakeDevicePort port = new FakeDevicePort();
        DeviceActorRuntime runtime = new DeviceActorRuntime(actor, DeviceActorRuntimeTest::createProcedure,
                () -> true, port);

        runtime.start(new SettleLinkProcedure(5_000), "test-settle");

        assertEffects(runtime.drainUnhandledEffects(), SettleLinkProcedure.EFFECT_SCHEDULE_LINK_SETTLE_TIMER);
    }

    private static @Nullable DeviceProcedure createProcedure(DeviceProcedureName procedureName) {
        if (procedureName == DeviceProcedureName.SETTLE_LINK) {
            return new SettleLinkProcedure(5_000);
        }
        if (procedureName == DeviceProcedureName.RESOLVE_GATT) {
            return new ResolveGattProcedure(120_000);
        }
        return null;
    }

    private static void assertEffects(List<DeviceEffect> effects, String... operations) {
        assertEquals(operations.length, effects.size());
        for (int i = 0; i < operations.length; i++) {
            assertEquals(operations[i], effects.get(i).operation());
        }
    }
}
