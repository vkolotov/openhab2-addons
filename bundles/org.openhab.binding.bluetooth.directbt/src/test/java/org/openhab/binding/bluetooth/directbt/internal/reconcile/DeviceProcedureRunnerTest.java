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

import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Pure tests for actor-owned procedure handoff interpretation.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
class DeviceProcedureRunnerTest {
    private static final long START = 1_784_200_000_000L;

    @Test
    void connectSettleResolveHandoffsKeepGenerationAndExposeOnlyExternalEffects() {
        DeviceActor actor = new DeviceActor("test-device", ReconcileTestSupport.logger(), new MutableClock(START));
        DeviceProcedureRunner runner = new DeviceProcedureRunner(actor, DeviceProcedureRunnerTest::createProcedure);

        runner.start(new ConnectProcedure(30_000), "wanted-online");
        long generation = actor.diagnostics().generation();
        assertEffects(runner.drainEffects(), ConnectProcedure.EFFECT_REQUEST_CONNECT_LEASE);

        runner.submit(new DeviceEvent.ConnectLeaseGranted(generation));
        assertEffects(runner.drainEffects(), ConnectProcedure.EFFECT_CONNECT_LE);

        runner.submit(new DeviceEvent.NativeConnected(generation));
        assertEquals(generation, actor.diagnostics().generation());
        assertEquals(DeviceActorState.LINK_SETTLING, actor.diagnostics().state());
        assertEffects(runner.drainEffects(), SettleLinkProcedure.EFFECT_SCHEDULE_LINK_SETTLE_TIMER);

        runner.submit(new DeviceEvent.LinkSettleTimerExpired(generation));
        assertEquals(generation, actor.diagnostics().generation());
        assertEquals(DeviceActorState.RESOLVING_GATT, actor.diagnostics().state());
        assertEffects(runner.drainEffects(), ResolveGattProcedure.EFFECT_RESOLVE_GATT);

        runner.submit(new DeviceEvent.GattResolveSucceeded(generation));
        assertEquals(generation, actor.diagnostics().generation());
        assertEquals(DeviceActorState.SUBSCRIBING, actor.diagnostics().state());
        assertEffects(runner.drainEffects(), ResolveGattProcedure.EFFECT_START_SUBSCRIBE_PROCEDURE);
    }

    @Test
    void replacedProcedureCleanupFromPreviousGenerationIsNotExposed() {
        DeviceActor actor = new DeviceActor("test-device", ReconcileTestSupport.logger(), new MutableClock(START));
        DeviceProcedureRunner runner = new DeviceProcedureRunner(actor, DeviceProcedureRunnerTest::createProcedure);

        runner.start(new ConnectProcedure(30_000), "first-connect");
        long firstGeneration = actor.diagnostics().generation();
        assertEffects(runner.drainEffects(), ConnectProcedure.EFFECT_REQUEST_CONNECT_LEASE);

        runner.submit(new DeviceEvent.ConnectLeaseGranted(firstGeneration));
        assertEffects(runner.drainEffects(), ConnectProcedure.EFFECT_CONNECT_LE);

        runner.start(new ConnectProcedure(30_000), "retry-connect");

        assertEquals(firstGeneration + 1, actor.diagnostics().generation());
        assertEffects(runner.drainEffects(), ConnectProcedure.EFFECT_REQUEST_CONNECT_LEASE);
    }

    @Test
    void wantedOfflineCleanupIsExposedInCurrentGeneration() {
        DeviceActor actor = new DeviceActor("test-device", ReconcileTestSupport.logger(), new MutableClock(START));
        DeviceProcedureRunner runner = new DeviceProcedureRunner(actor, DeviceProcedureRunnerTest::createProcedure);

        runner.start(new ConnectProcedure(30_000), "wanted-online");
        long onlineGeneration = actor.diagnostics().generation();
        assertEffects(runner.drainEffects(), ConnectProcedure.EFFECT_REQUEST_CONNECT_LEASE);

        runner.submit(new DeviceEvent.WantedOffline());

        assertEquals(onlineGeneration + 1, actor.diagnostics().generation());
        assertEquals(DeviceActorState.DISCONNECTING, actor.diagnostics().state());
        assertEffects(runner.drainEffects(), ConnectProcedure.EFFECT_DISCONNECT_NATIVE);
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
