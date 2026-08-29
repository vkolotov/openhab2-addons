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
package org.openhab.binding.bluetooth.directbt.internal.metrics;

import static org.junit.jupiter.api.Assertions.*;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.device.DeviceActorState;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.device.DeviceProcedureOutcome;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.device.DeviceWaitingOn;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.procedure.DeviceProcedureName;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Locks down the control-plane metric contract: the queries that answer "where does setup time go", "are we
 * thrashing" and "are the deadlines tuned" depend on these names, tags and attribution rules.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
class ActorMetricsListenerTest {

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final Tags baseTags = Tags.of("device", "Test Device", "address", "11:22:33:44:55:66", "adapter",
            "AA:BB:CC:DD:EE:FF");

    private @NonNullByDefault({}) ActorMetricsListener listener;

    @BeforeEach
    void setUp() {
        DeviceMetrics metrics = new DeviceMetrics(registry, "Test Device", "11:22:33:44:55:66", "AA:BB:CC:DD:EE:FF");
        listener = new ActorMetricsListener(() -> metrics);
    }

    @Test
    void stateTimeIsAttributedToTheStateBeingLeftNotTheOneEntered() {
        // The elapsed time belongs to the state whose residency just ENDED. Attributing it to the new state would
        // shift every measurement one transition into the future and make the time budget meaningless.
        listener.onTransition(DeviceActorState.CONNECTING, DeviceWaitingOn.CONNECT_LEASE, DeviceActorState.CONNECTING,
                DeviceWaitingOn.NATIVE_CONNECT, 12_000, "ConnectLeaseGranted", DeviceProcedureName.CONNECT);

        assertEquals(12.0,
                registry.get("openhab.bluetooth.state.seconds.total")
                        .tags(baseTags.and("state", "CONNECTING").and("waiting_on", "CONNECT_LEASE")).counter().count(),
                0.001);
        assertNull(
                registry.find("openhab.bluetooth.state.seconds.total")
                        .tags(baseTags.and("state", "CONNECTING").and("waiting_on", "NATIVE_CONNECT")).counter(),
                "the state being entered has not accrued any time yet");
    }

    @Test
    void radioWaitIsSeparableFromPeerWait() {
        // The whole point of the waiting_on tag: time lost to radio arbitration and time lost to an unresponsive
        // peer have opposite remedies, so they must never land in one bucket.
        listener.onTransition(DeviceActorState.CONNECTING, DeviceWaitingOn.CONNECT_LEASE, DeviceActorState.CONNECTING,
                DeviceWaitingOn.NATIVE_CONNECT, 30_000, "ConnectLeaseGranted", DeviceProcedureName.CONNECT);
        listener.onTransition(DeviceActorState.CONNECTING, DeviceWaitingOn.NATIVE_CONNECT,
                DeviceActorState.LINK_SETTLING, DeviceWaitingOn.SETTLE_TIMER, 400, "NativeConnected",
                DeviceProcedureName.CONNECT);

        double lease = registry.get("openhab.bluetooth.state.seconds.total")
                .tags(baseTags.and("state", "CONNECTING").and("waiting_on", "CONNECT_LEASE")).counter().count();
        double peer = registry.get("openhab.bluetooth.state.seconds.total")
                .tags(baseTags.and("state", "CONNECTING").and("waiting_on", "NATIVE_CONNECT")).counter().count();

        assertEquals(30.0, lease, 0.001);
        assertEquals(0.4, peer, 0.001);
        assertTrue(lease > peer, "this device spent its setup time waiting for the radio, not for the peer");
    }

    @Test
    void generationChurnIsCounted() {
        // Each generation advance fences in-flight work: it IS a control-plane restart, so the rate of this
        // counter is the thrashing signal.
        listener.onGenerationAdvanced(1, "WantedOnline");
        listener.onGenerationAdvanced(2, "ConnectFailed");
        listener.onGenerationAdvanced(3, "AdapterResetStarted");

        assertEquals(3.0, registry.get("openhab.bluetooth.generation.total").tags(baseTags).counter().count());
    }

    @Test
    void procedureOutcomesAreSeparatedSoSuccessRateIsPerProcedure() {
        listener.onProcedureFinished(DeviceProcedureName.CONNECT, DeviceProcedureOutcome.HANDED_OFF, 800);
        listener.onProcedureFinished(DeviceProcedureName.CONNECT, DeviceProcedureOutcome.DEADLINE_EXPIRED, 45_000);
        listener.onProcedureFinished(DeviceProcedureName.RESOLVE_GATT, DeviceProcedureOutcome.HANDED_OFF, 7_000);

        assertEquals(1.0, registry.get("openhab.bluetooth.procedure.total")
                .tags(baseTags.and("procedure", "CONNECT").and("outcome", "handed_off")).counter().count());
        assertEquals(1.0, registry.get("openhab.bluetooth.procedure.total")
                .tags(baseTags.and("procedure", "CONNECT").and("outcome", "deadline_expired")).counter().count());
        assertEquals(1.0, registry.get("openhab.bluetooth.procedure.total")
                .tags(baseTags.and("procedure", "RESOLVE_GATT").and("outcome", "handed_off")).counter().count());
    }

    @Test
    void procedureDurationIsTimedPerProcedure() {
        listener.onProcedureFinished(DeviceProcedureName.RESOLVE_GATT, DeviceProcedureOutcome.HANDED_OFF, 7_000);

        assertEquals(7000.0,
                registry.get("openhab.bluetooth.procedure.duration")
                        .tags(baseTags.and("procedure", "RESOLVE_GATT").and("outcome", "handed_off")).timer()
                        .totalTime(java.util.concurrent.TimeUnit.MILLISECONDS),
                1.0);
    }

    @Test
    void deadlinesAreTaggedByWhatBlockedThemSoTheyCanBeTuned() {
        // Same procedure, opposite remedies: a CONNECT_LEASE expiry means the radio never freed up (arbitration),
        // a NATIVE_CONNECT expiry means the peer never answered (link).
        listener.onDeadlineExceeded(DeviceProcedureName.CONNECT, DeviceWaitingOn.CONNECT_LEASE, 45_000);
        listener.onDeadlineExceeded(DeviceProcedureName.CONNECT, DeviceWaitingOn.NATIVE_CONNECT, 8_000);

        assertEquals(1.0, registry.get("openhab.bluetooth.deadline.total")
                .tags(baseTags.and("procedure", "CONNECT").and("waiting_on", "CONNECT_LEASE")).counter().count());
        assertEquals(1.0, registry.get("openhab.bluetooth.deadline.total")
                .tags(baseTags.and("procedure", "CONNECT").and("waiting_on", "NATIVE_CONNECT")).counter().count());
    }

    @Test
    void transitionsAreCountedSoALongResidencyIsDistinguishableFromManyShortOnes() {
        for (int i = 0; i < 4; i++) {
            listener.onTransition(DeviceActorState.BACKING_OFF, DeviceWaitingOn.BACKOFF_TIMER,
                    DeviceActorState.CONNECTING, DeviceWaitingOn.CONNECT_LEASE, 2_000, "retry",
                    DeviceProcedureName.CONNECT);
        }

        assertEquals(4.0, registry.get("openhab.bluetooth.transitions.total")
                .tags(baseTags.and("state", "CONNECTING").and("waiting_on", "CONNECT_LEASE")).counter().count());
        assertEquals(8.0, registry.get("openhab.bluetooth.state.seconds.total")
                .tags(baseTags.and("state", "BACKING_OFF").and("waiting_on", "BACKOFF_TIMER")).counter().count(),
                0.001);
    }
}
