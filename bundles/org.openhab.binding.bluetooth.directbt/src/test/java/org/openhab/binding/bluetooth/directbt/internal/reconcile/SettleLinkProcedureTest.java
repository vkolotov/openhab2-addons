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

/**
 * Pure contract tests for the post-connect settle procedure.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
class SettleLinkProcedureTest {
    private static final long START = 1_784_200_000_000L;

    @Test
    void startSchedulesSettleTimerAndDoesNotResolveGatt() {
        DeviceActor actor = newActor();

        actor.startProcedure(new SettleLinkProcedure(5_000), "native-connected");

        List<DeviceEffect> effects = actor.drainEffects();
        assertEquals(1, effects.size());
        assertEquals(SettleLinkProcedure.EFFECT_SCHEDULE_LINK_SETTLE_TIMER, effects.get(0).operation());
        assertEquals(DeviceActorState.LINK_SETTLING, actor.diagnostics().state());
        assertEquals(DeviceWaitingOn.SETTLE_TIMER, actor.diagnostics().waitingOn());
    }

    @Test
    void settleTimerHandsOffToGattResolveProcedure() {
        DeviceActor actor = newActor();
        actor.startProcedure(new SettleLinkProcedure(5_000), "native-connected");
        actor.drainEffects();
        long generation = actor.diagnostics().generation();

        actor.submit(new DeviceEvent.LinkSettleTimerExpired(generation));

        List<DeviceEffect> effects = actor.drainEffects();
        assertEquals(1, effects.size());
        assertEquals(SettleLinkProcedure.EFFECT_START_RESOLVE_GATT_PROCEDURE, effects.get(0).operation());
        assertEquals(DeviceActorState.RESOLVING_GATT, actor.diagnostics().state());
        assertEquals(DeviceWaitingOn.GATT_RESOLVE, actor.diagnostics().waitingOn());
    }

    @Test
    void staleSettleTimerIsIgnored() {
        DeviceActor actor = newActor();
        actor.startProcedure(new SettleLinkProcedure(5_000), "native-connected");
        actor.drainEffects();
        long generation = actor.diagnostics().generation();

        actor.submit(new DeviceEvent.LinkSettleTimerExpired(generation - 1));

        assertEquals(0, actor.drainEffects().size());
        assertEquals(DeviceActorState.LINK_SETTLING, actor.diagnostics().state());
        assertEquals(DeviceWaitingOn.SETTLE_TIMER, actor.diagnostics().waitingOn());
    }

    @Test
    void deadlineDisconnectsNativeAndBacksOff() {
        MutableClock clock = new MutableClock(START);
        DeviceActor actor = new DeviceActor("test-device", ReconcileTestSupport.logger(), clock);
        actor.startProcedure(new SettleLinkProcedure(1_000), "native-connected");
        actor.drainEffects();

        clock.advance(1_000);
        actor.tick();

        List<DeviceEffect> effects = actor.drainEffects();
        assertEquals(1, effects.size());
        assertEquals(SettleLinkProcedure.EFFECT_DISCONNECT_NATIVE, effects.get(0).operation());
        assertEquals(DeviceActorState.BACKING_OFF, actor.diagnostics().state());
        assertEquals(DeviceWaitingOn.BACKOFF_TIMER, actor.diagnostics().waitingOn());
    }

    private static DeviceActor newActor() {
        return new DeviceActor("test-device", ReconcileTestSupport.logger(), new MutableClock(START));
    }
}
