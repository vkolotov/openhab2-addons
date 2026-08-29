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
import org.junit.jupiter.api.Test;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.adapter.*;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.device.*;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.effect.*;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.event.*;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.port.*;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.procedure.*;

/**
 * Pure contract tests for the subscription/connection-ready procedure.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
class SubscribeNotificationsProcedureTest {
    private static final long START = 1_784_200_000_000L;

    @Test
    void startMarksConnectedThroughPortEffect() {
        DeviceActor actor = newActor();

        actor.startProcedure(new SubscribeNotificationsProcedure(5_000), "gatt-resolved");

        List<DeviceEffect> effects = actor.drainEffects();
        assertEquals(1, effects.size());
        assertEquals(DeviceEffectOperation.MARK_CONNECTED, effects.get(0).operation());
        assertEquals(DeviceActorState.SUBSCRIBING, actor.diagnostics().state());
        assertEquals(DeviceWaitingOn.SUBSCRIPTION, actor.diagnostics().waitingOn());
    }

    @Test
    void markConnectedCompletionHandsOffToOnlineMonitor() {
        DeviceActor actor = newActor();
        actor.startProcedure(new SubscribeNotificationsProcedure(5_000), "gatt-resolved");
        actor.drainEffects();
        long generation = actor.diagnostics().generation();

        actor.submit(
                new DeviceEvent.NativeEffectCompleted(generation, DeviceEffectOperation.MARK_CONNECTED, "SUCCESS"));

        List<DeviceEffect> effects = actor.drainEffects();
        assertEquals(1, effects.size());
        assertEquals(DeviceEffectOperation.START_ONLINE_MONITOR_PROCEDURE, effects.get(0).operation());
        assertEquals(DeviceActorState.SUBSCRIBING, actor.diagnostics().state());
        assertEquals(DeviceWaitingOn.SUBSCRIPTION, actor.diagnostics().waitingOn());
    }

    @Test
    void deadlineDisconnectsNativeAndBacksOff() {
        MutableClock clock = new MutableClock(START);
        DeviceActor actor = new DeviceActor("test-device", ReconcileTestSupport.logger(), clock);
        actor.startProcedure(new SubscribeNotificationsProcedure(1_000), "gatt-resolved");
        actor.drainEffects();

        clock.advance(1_000);
        actor.tick();

        List<DeviceEffect> effects = actor.drainEffects();
        assertEquals(1, effects.size());
        assertEquals(DeviceEffectOperation.DISCONNECT_NATIVE, effects.get(0).operation());
        assertEquals(DeviceActorState.BACKING_OFF, actor.diagnostics().state());
        assertEquals(DeviceWaitingOn.BACKOFF_TIMER, actor.diagnostics().waitingOn());
    }

    private static DeviceActor newActor() {
        return new DeviceActor("test-device", ReconcileTestSupport.logger(), new MutableClock(START));
    }
}
