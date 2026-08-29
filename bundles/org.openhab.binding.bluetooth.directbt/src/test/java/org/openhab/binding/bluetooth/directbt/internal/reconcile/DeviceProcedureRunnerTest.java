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
import org.openhab.binding.bluetooth.directbt.internal.reconcile.adapter.*;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.device.*;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.effect.*;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.event.*;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.port.*;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.procedure.*;

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
        assertEffects(runner.drainEffects(), DeviceEffectOperation.REQUEST_CONNECT_LEASE);

        runner.submit(new DeviceEvent.ConnectLeaseGranted(generation));
        assertEffects(runner.drainEffects(), DeviceEffectOperation.CONNECT_LE);

        runner.submit(new DeviceEvent.NativeConnected(generation));
        assertEquals(generation, actor.diagnostics().generation());
        assertEquals(DeviceActorState.LINK_SETTLING, actor.diagnostics().state());
        assertEffects(runner.drainEffects(), DeviceEffectOperation.SCHEDULE_LINK_SETTLE_TIMER);

        runner.submit(new DeviceEvent.LinkSettleTimerExpired(generation));
        assertEquals(generation, actor.diagnostics().generation());
        assertEquals(DeviceActorState.RESOLVING_GATT, actor.diagnostics().state());
        assertEffects(runner.drainEffects(), DeviceEffectOperation.RESOLVE_GATT);

        runner.submit(new DeviceEvent.GattResolveSucceeded(generation));
        assertEquals(generation, actor.diagnostics().generation());
        assertEquals(DeviceActorState.SUBSCRIBING, actor.diagnostics().state());
        assertEffects(runner.drainEffects(), DeviceEffectOperation.START_SUBSCRIBE_PROCEDURE);
    }

    @Test
    void replacedProcedureCleanupFromPreviousGenerationIsNotExposed() {
        DeviceActor actor = new DeviceActor("test-device", ReconcileTestSupport.logger(), new MutableClock(START));
        DeviceProcedureRunner runner = new DeviceProcedureRunner(actor, DeviceProcedureRunnerTest::createProcedure);

        runner.start(new ConnectProcedure(30_000), "first-connect");
        long firstGeneration = actor.diagnostics().generation();
        assertEffects(runner.drainEffects(), DeviceEffectOperation.REQUEST_CONNECT_LEASE);

        runner.submit(new DeviceEvent.ConnectLeaseGranted(firstGeneration));
        assertEffects(runner.drainEffects(), DeviceEffectOperation.CONNECT_LE);

        runner.start(new ConnectProcedure(30_000), "retry-connect");

        assertEquals(firstGeneration + 1, actor.diagnostics().generation());
        assertEffects(runner.drainEffects(), DeviceEffectOperation.REQUEST_CONNECT_LEASE);
    }

    @Test
    void wantedOfflineCleanupIsExposedInCurrentGeneration() {
        DeviceActor actor = new DeviceActor("test-device", ReconcileTestSupport.logger(), new MutableClock(START));
        DeviceProcedureRunner runner = new DeviceProcedureRunner(actor, DeviceProcedureRunnerTest::createProcedure);

        runner.start(new ConnectProcedure(30_000), "wanted-online");
        long onlineGeneration = actor.diagnostics().generation();
        assertEffects(runner.drainEffects(), DeviceEffectOperation.REQUEST_CONNECT_LEASE);

        runner.submit(new DeviceEvent.WantedOffline());

        assertEquals(onlineGeneration + 1, actor.diagnostics().generation());
        assertEquals(DeviceActorState.DISCONNECTING, actor.diagnostics().state());
        assertEffects(runner.drainEffects(), DeviceEffectOperation.DISCONNECT_NATIVE);
    }

    @Test
    void subscribeCompletionHandoffsToOnlineMonitor() {
        MutableClock clock = new MutableClock(START);
        DeviceActor actor = new DeviceActor("test-device", ReconcileTestSupport.logger(), clock);
        DeviceProcedureRunner runner = new DeviceProcedureRunner(actor, DeviceProcedureRunnerTest::createProcedure);

        runner.start(new SubscribeNotificationsProcedure(5_000), "gatt-resolved");
        long generation = actor.diagnostics().generation();
        assertEffects(runner.drainEffects(), DeviceEffectOperation.MARK_CONNECTED);

        runner.submit(
                new DeviceEvent.NativeEffectCompleted(generation, DeviceEffectOperation.MARK_CONNECTED, "SUCCESS"));

        assertEquals(generation, actor.diagnostics().generation());
        assertEquals(DeviceActorState.ONLINE, actor.diagnostics().state());
        assertEquals(DeviceWaitingOn.NOTHING, actor.diagnostics().waitingOn());
        assertEffects(runner.drainEffects());

        clock.advance(60_000);
        runner.tick();

        assertEquals(DeviceActorState.ONLINE, actor.diagnostics().state());
        assertEffects(runner.drainEffects());
    }

    private static @Nullable DeviceProcedure createProcedure(DeviceProcedureName procedureName) {
        if (procedureName == DeviceProcedureName.SETTLE_LINK) {
            return new SettleLinkProcedure(5_000);
        }
        if (procedureName == DeviceProcedureName.RESOLVE_GATT) {
            return new ResolveGattProcedure(120_000);
        }
        if (procedureName == DeviceProcedureName.ONLINE_MONITOR) {
            return new OnlineMonitorProcedure();
        }
        return null;
    }

    private static void assertEffects(List<DeviceEffect> effects, DeviceEffectOperation... operations) {
        assertEquals(operations.length, effects.size());
        for (int i = 0; i < operations.length; i++) {
            assertEquals(operations[i], effects.get(i).operation());
        }
    }
}
