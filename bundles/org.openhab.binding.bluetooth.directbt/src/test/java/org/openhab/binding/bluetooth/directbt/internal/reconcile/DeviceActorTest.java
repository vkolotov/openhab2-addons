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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for the shadow actor runtime, before production device procedures are migrated into it.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
class DeviceActorTest {
    private static final long START = 1_784_200_000_000L;

    @Test
    void nonIdleProceduresMustDeclareADeadline() {
        DeviceActor actor = new DeviceActor("test-device", ReconcileTestSupport.logger(), new MutableClock(START));

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> actor.startProcedure(new FakeProcedure(0), "test"));

        String message = thrown.getMessage();
        assertNotNull(message);
        assertTrue(message.contains("has no deadline"));
    }

    @Test
    void deadlineIsDeliveredAsGenerationTaggedEvent() {
        MutableClock clock = new MutableClock(START);
        DeviceActor actor = new DeviceActor("test-device", ReconcileTestSupport.logger(), clock);
        FakeProcedure procedure = new FakeProcedure(1000);

        actor.startProcedure(procedure, "test-start");
        long generation = actor.diagnostics().generation();
        clock.advance(999);
        actor.tick();
        assertEquals(0, procedure.events.size());

        clock.advance(1);
        actor.tick();

        assertEquals(1, procedure.events.size());
        DeviceEvent event = procedure.events.get(0);
        assertTrue(event instanceof DeviceEvent.ProcedureDeadlineExpired);
        assertEquals(generation, event.generation());
        assertEquals(1000, ((DeviceEvent.ProcedureDeadlineExpired) event).elapsedMs());
    }

    @Test
    void staleGenerationEventsAreIgnored() {
        MutableClock clock = new MutableClock(START);
        DeviceActor actor = new DeviceActor("test-device", ReconcileTestSupport.logger(), clock);
        FakeProcedure procedure = new FakeProcedure(1000);

        actor.startProcedure(procedure, "test-start");
        long generation = actor.diagnostics().generation();

        actor.submit(new DeviceEvent.NativeEffectCompleted(generation - 1, "connectLE", "late-success"));
        actor.submit(new DeviceEvent.NativeEffectCompleted(generation, "connectLE", "success"));

        assertEquals(1, procedure.events.size());
        assertEquals("success", ((DeviceEvent.NativeEffectCompleted) procedure.events.get(0)).result());
    }

    @Test
    void adapterResetInvalidatesProcedureAndReturnsWantedDeviceToDiscovery() {
        MutableClock clock = new MutableClock(START);
        DeviceActor actor = new DeviceActor("test-device", ReconcileTestSupport.logger(), clock);
        FakeProcedure procedure = new FakeProcedure(1000);
        actor.submit(new DeviceEvent.WantedOnline());
        actor.startProcedure(procedure, "test-start");
        long beforeReset = actor.diagnostics().generation();

        actor.submit(new DeviceEvent.AdapterResetStarted(41));
        assertEquals(1, procedure.cancelReasons.size());
        assertEquals(DeviceActorState.BACKING_OFF, actor.diagnostics().state());
        assertEquals(DeviceWaitingOn.ADAPTER_RESET, actor.diagnostics().waitingOn());
        assertTrue(actor.diagnostics().generation() > beforeReset);

        long afterStarted = actor.diagnostics().generation();
        actor.submit(new DeviceEvent.AdapterResetCompleted(42, "SUCCESS"));

        assertTrue(actor.diagnostics().generation() > afterStarted);
        assertEquals(DeviceActorState.DISCOVERING, actor.diagnostics().state());
        assertEquals(DeviceWaitingOn.NATIVE_HANDLE, actor.diagnostics().waitingOn());
    }

    @Test
    void proceduresEmitEffectsInsteadOfRunningNativeWorkInline() {
        DeviceActor actor = new DeviceActor("test-device", ReconcileTestSupport.logger(), new MutableClock(START));
        FakeProcedure procedure = new FakeProcedure(1000);

        actor.startProcedure(procedure, "test-start");

        List<DeviceEffect> effects = actor.drainEffects();
        assertEquals(1, effects.size());
        assertEquals(actor.diagnostics().generation(), effects.get(0).generation());
        assertEquals("connectLE", effects.get(0).operation());
    }

    private static final class FakeProcedure implements DeviceProcedure {
        final List<DeviceEvent> events = new ArrayList<>();
        final List<String> cancelReasons = new ArrayList<>();
        private final long maxResidencyMs;

        FakeProcedure(long maxResidencyMs) {
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
            ctx.emit(new DeviceEffect(ctx.generation(), "connectLE"));
        }

        @Override
        public void onEvent(DeviceEvent event, DeviceProcedureContext ctx) {
            events.add(event);
        }

        @Override
        public void cancel(String reason, DeviceProcedureContext ctx) {
            cancelReasons.add(reason);
        }
    }
}
