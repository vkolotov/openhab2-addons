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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.adapter.*;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.device.*;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.effect.*;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.event.*;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.port.*;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.procedure.*;

/**
 * Tests for actor backoff cleanup policy.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
class DeviceBackoffPolicyTest {
    @Test
    void backingOffMarksDisconnectedOncePerGenerationWithoutTouchingTheBond() {
        FakeDevicePort port = new FakeDevicePort();
        port.prePaired = true;
        DeviceBackoffPolicy policy = new DeviceBackoffPolicy(port);

        assertTrue(policy.apply(diagnostics(41, DeviceActorState.BACKING_OFF)));
        assertTrue(port.prePaired, "BACKING_OFF is reached from non-connect teardowns too; bond clearing is "
                + "evidence-driven via the CONNECT procedure's clear effect, never a backoff side-effect");
        assertEquals(0, port.clearStalePairingCalls);
        assertEquals(1, port.markDisconnectedCalls);

        assertFalse(policy.apply(diagnostics(41, DeviceActorState.BACKING_OFF)));
        assertEquals(1, port.markDisconnectedCalls);
    }

    @Test
    void backingOffWithoutStalePairingOnlyMarksDisconnected() {
        FakeDevicePort port = new FakeDevicePort();
        DeviceBackoffPolicy policy = new DeviceBackoffPolicy(port);

        assertTrue(policy.apply(diagnostics(41, DeviceActorState.BACKING_OFF)));

        assertEquals(0, port.clearStalePairingCalls);
        assertEquals(1, port.markDisconnectedCalls);
    }

    @Test
    void nonBackoffStateDoesNothing() {
        FakeDevicePort port = new FakeDevicePort();
        port.prePaired = true;
        DeviceBackoffPolicy policy = new DeviceBackoffPolicy(port);

        assertFalse(policy.apply(diagnostics(41, DeviceActorState.CONNECTING)));

        assertEquals(0, port.clearStalePairingCalls);
        assertEquals(0, port.markDisconnectedCalls);
    }

    @Test
    void laterBackoffGenerationIsAppliedAgain() {
        FakeDevicePort port = new FakeDevicePort();
        DeviceBackoffPolicy policy = new DeviceBackoffPolicy(port);

        assertTrue(policy.apply(diagnostics(41, DeviceActorState.BACKING_OFF)));
        assertTrue(policy.apply(diagnostics(42, DeviceActorState.BACKING_OFF)));

        assertEquals(2, port.markDisconnectedCalls);
    }

    @Test
    void adapterResetBackoffInvalidatesModelWithoutNativeCleanup() {
        FakeDevicePort port = new FakeDevicePort();
        port.hasNative = true;
        port.nativeConnected = true;
        DeviceBackoffPolicy policy = new DeviceBackoffPolicy(port);

        assertTrue(policy.apply(diagnostics(41, DeviceActorState.BACKING_OFF, DeviceWaitingOn.ADAPTER_RESET)));

        assertEquals(0, port.markDisconnectedCalls);
        assertEquals(1, port.markDisconnectedByAdapterResetCalls);
        assertFalse(port.hasNative);
        assertFalse(port.nativeConnected);
    }

    private static DeviceActorDiagnostics diagnostics(long generation, DeviceActorState state) {
        return diagnostics(generation, state, DeviceWaitingOn.BACKOFF_TIMER);
    }

    private static DeviceActorDiagnostics diagnostics(long generation, DeviceActorState state,
            DeviceWaitingOn waitingOn) {
        return new DeviceActorDiagnostics("test-device", generation, state, waitingOn, 0, 0, "test", null);
    }
}
