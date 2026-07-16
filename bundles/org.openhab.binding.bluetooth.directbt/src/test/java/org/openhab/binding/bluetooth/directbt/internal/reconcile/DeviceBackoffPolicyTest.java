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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;

/**
 * Tests for actor backoff cleanup policy.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
class DeviceBackoffPolicyTest {
    @Test
    void backingOffClearsStalePairingAndMarksDisconnectedOncePerGeneration() {
        FakeDevicePort port = new FakeDevicePort();
        port.prePaired = true;
        DeviceBackoffPolicy policy = new DeviceBackoffPolicy(port);

        assertTrue(policy.apply(diagnostics(41, DeviceActorState.BACKING_OFF)));
        assertFalse(port.prePaired);
        assertEquals(1, port.clearStalePairingCalls);
        assertEquals(1, port.markDisconnectedCalls);

        assertFalse(policy.apply(diagnostics(41, DeviceActorState.BACKING_OFF)));
        assertEquals(1, port.clearStalePairingCalls);
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

    private static DeviceActorDiagnostics diagnostics(long generation, DeviceActorState state) {
        return new DeviceActorDiagnostics("test-device", generation, state, DeviceWaitingOn.BACKOFF_TIMER, 0, 0,
                "test", null);
    }
}
