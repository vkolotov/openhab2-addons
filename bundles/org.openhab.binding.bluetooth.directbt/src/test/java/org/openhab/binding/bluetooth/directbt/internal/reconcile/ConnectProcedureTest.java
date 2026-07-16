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
 * Pure contract tests for the connect procedure before production wiring.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
class ConnectProcedureTest {
    private static final long START = 1_784_200_000_000L;

    @Test
    void startRequestsConnectLeaseBeforeNativeConnect() {
        DeviceActor actor = newActor();

        actor.startProcedure(new ConnectProcedure(30_000), "test-start");

        List<DeviceEffect> effects = actor.drainEffects();
        assertEquals(1, effects.size());
        assertEquals(ConnectProcedure.EFFECT_REQUEST_CONNECT_LEASE, effects.get(0).operation());
        assertEquals(DeviceActorState.CONNECTING, actor.diagnostics().state());
        assertEquals(DeviceWaitingOn.CONNECT_LEASE, actor.diagnostics().waitingOn());
    }

    @Test
    void leaseGrantStartsNativeConnect() {
        DeviceActor actor = newActor();
        actor.startProcedure(new ConnectProcedure(30_000), "test-start");
        actor.drainEffects();
        long generation = actor.diagnostics().generation();

        actor.submit(new DeviceEvent.ConnectLeaseGranted(generation));

        List<DeviceEffect> effects = actor.drainEffects();
        assertEquals(1, effects.size());
        assertEquals(ConnectProcedure.EFFECT_CONNECT_LE, effects.get(0).operation());
        assertEquals(DeviceActorState.CONNECTING, actor.diagnostics().state());
        assertEquals(DeviceWaitingOn.NATIVE_CONNECT, actor.diagnostics().waitingOn());
    }

    @Test
    void staleLeaseGrantIsIgnored() {
        DeviceActor actor = newActor();
        actor.startProcedure(new ConnectProcedure(30_000), "test-start");
        actor.drainEffects();
        long generation = actor.diagnostics().generation();

        actor.submit(new DeviceEvent.ConnectLeaseGranted(generation - 1));

        assertEquals(0, actor.drainEffects().size());
        assertEquals(DeviceWaitingOn.CONNECT_LEASE, actor.diagnostics().waitingOn());
    }

    @Test
    void pairingTemporarilyChangesDiagnosticWaitState() {
        DeviceActor actor = newActor();
        actor.startProcedure(new ConnectProcedure(30_000), "test-start");
        long generation = actor.diagnostics().generation();

        actor.submit(new DeviceEvent.PairingStarted(generation));
        assertEquals(DeviceWaitingOn.PAIRING, actor.diagnostics().waitingOn());

        actor.submit(new DeviceEvent.PairingEnded(generation));
        assertEquals(DeviceWaitingOn.NATIVE_CONNECT, actor.diagnostics().waitingOn());
    }

    @Test
    void nativeConnectedHandsOffToLinkSettling() {
        DeviceActor actor = newActor();
        actor.startProcedure(new ConnectProcedure(30_000), "test-start");
        long generation = actor.diagnostics().generation();

        actor.submit(new DeviceEvent.NativeConnected(generation));

        assertEquals(DeviceActorState.LINK_SETTLING, actor.diagnostics().state());
        assertEquals(DeviceWaitingOn.SETTLE_TIMER, actor.diagnostics().waitingOn());
    }

    @Test
    void deadlineDisconnectsNativeAndBacksOff() {
        MutableClock clock = new MutableClock(START);
        DeviceActor actor = new DeviceActor("test-device", ReconcileTestSupport.logger(), clock);
        actor.startProcedure(new ConnectProcedure(1_000), "test-start");
        actor.drainEffects();

        clock.advance(1_000);
        actor.tick();

        List<DeviceEffect> effects = actor.drainEffects();
        assertEquals(1, effects.size());
        assertEquals(ConnectProcedure.EFFECT_DISCONNECT_NATIVE, effects.get(0).operation());
        assertEquals(DeviceActorState.BACKING_OFF, actor.diagnostics().state());
        assertEquals(DeviceWaitingOn.BACKOFF_TIMER, actor.diagnostics().waitingOn());
    }

    @Test
    void staleBondFailureClearsPairingBeforeBackoff() {
        DeviceActor actor = newActor();
        actor.startProcedure(new ConnectProcedure(30_000), "test-start");
        actor.drainEffects();
        long generation = actor.diagnostics().generation();

        actor.submit(new DeviceEvent.ConnectFailed(generation, "AUTH_FAIL", true));

        List<DeviceEffect> effects = actor.drainEffects();
        assertEquals(2, effects.size());
        assertEquals(ConnectProcedure.EFFECT_DISCONNECT_NATIVE, effects.get(0).operation());
        assertEquals(ConnectProcedure.EFFECT_CLEAR_STALE_PAIRING, effects.get(1).operation());
        assertEquals(DeviceActorState.BACKING_OFF, actor.diagnostics().state());
        assertEquals(DeviceWaitingOn.BACKOFF_TIMER, actor.diagnostics().waitingOn());
    }

    @Test
    void ordinaryFailureDoesNotClearPairing() {
        DeviceActor actor = newActor();
        actor.startProcedure(new ConnectProcedure(30_000), "test-start");
        actor.drainEffects();
        long generation = actor.diagnostics().generation();

        actor.submit(new DeviceEvent.ConnectFailed(generation, "TIMEOUT", false));

        List<DeviceEffect> effects = actor.drainEffects();
        assertEquals(1, effects.size());
        assertEquals(ConnectProcedure.EFFECT_DISCONNECT_NATIVE, effects.get(0).operation());
        assertEquals(DeviceActorState.BACKING_OFF, actor.diagnostics().state());
    }

    private static DeviceActor newActor() {
        return new DeviceActor("test-device", ReconcileTestSupport.logger(), new MutableClock(START));
    }
}
