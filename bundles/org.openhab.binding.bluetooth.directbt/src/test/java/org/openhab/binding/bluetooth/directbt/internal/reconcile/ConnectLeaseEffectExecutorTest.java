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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;

/**
 * Tests for scan-off connect lease gating.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
class ConnectLeaseEffectExecutorTest {
    private static final long GENERATION = 41;

    @Test
    void grantsImmediatelyWhenScanIsAlreadyOff() {
        AtomicBoolean scanOff = new AtomicBoolean(true);
        List<DeviceEvent> events = new ArrayList<>();
        ConnectLeaseEffectExecutor executor = new ConnectLeaseEffectExecutor(scanOff::get, events::add);

        assertTrue(executor.execute(new DeviceEffect(GENERATION, ConnectProcedure.EFFECT_REQUEST_CONNECT_LEASE)));

        assertFalse(executor.hasPendingLease());
        assertLeaseGranted(events, GENERATION);
    }

    @Test
    void remembersLeaseUntilScanTurnsOff() {
        AtomicBoolean scanOff = new AtomicBoolean(false);
        List<DeviceEvent> events = new ArrayList<>();
        ConnectLeaseEffectExecutor executor = new ConnectLeaseEffectExecutor(scanOff::get, events::add);

        assertTrue(executor.execute(new DeviceEffect(GENERATION, ConnectProcedure.EFFECT_REQUEST_CONNECT_LEASE)));

        assertTrue(executor.hasPendingLease());
        assertEquals(0, events.size());

        scanOff.set(true);
        executor.tick(GENERATION);

        assertFalse(executor.hasPendingLease());
        assertLeaseGranted(events, GENERATION);
    }

    @Test
    void stalePendingLeaseIsDropped() {
        AtomicBoolean scanOff = new AtomicBoolean(false);
        List<DeviceEvent> events = new ArrayList<>();
        ConnectLeaseEffectExecutor executor = new ConnectLeaseEffectExecutor(scanOff::get, events::add);

        assertTrue(executor.execute(new DeviceEffect(GENERATION, ConnectProcedure.EFFECT_REQUEST_CONNECT_LEASE)));

        scanOff.set(true);
        executor.tick(GENERATION + 1);

        assertFalse(executor.hasPendingLease());
        assertEquals(0, events.size());
    }

    @Test
    void newerLeaseRequestReplacesOlderPendingRequest() {
        AtomicBoolean scanOff = new AtomicBoolean(false);
        List<DeviceEvent> events = new ArrayList<>();
        ConnectLeaseEffectExecutor executor = new ConnectLeaseEffectExecutor(scanOff::get, events::add);

        assertTrue(executor.execute(new DeviceEffect(GENERATION, ConnectProcedure.EFFECT_REQUEST_CONNECT_LEASE)));
        assertTrue(executor.execute(new DeviceEffect(GENERATION + 1, ConnectProcedure.EFFECT_REQUEST_CONNECT_LEASE)));

        scanOff.set(true);
        executor.tick(GENERATION + 1);

        assertLeaseGranted(events, GENERATION + 1);
    }

    @Test
    void unknownEffectIsLeftForAnotherExecutor() {
        AtomicBoolean scanOff = new AtomicBoolean(true);
        ConnectLeaseEffectExecutor executor = new ConnectLeaseEffectExecutor(scanOff::get, event -> {
        });

        assertFalse(executor.execute(new DeviceEffect(GENERATION, ConnectProcedure.EFFECT_CONNECT_LE)));
    }

    private static void assertLeaseGranted(List<DeviceEvent> events, long generation) {
        assertEquals(1, events.size());
        DeviceEvent event = events.get(0);
        assertTrue(event instanceof DeviceEvent.ConnectLeaseGranted);
        assertEquals(generation, event.generation());
    }
}
