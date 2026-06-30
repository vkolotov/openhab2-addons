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

import org.direct_bt.HCIStatusCode;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Regression coverage for the device connection intent semantics.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
class DeviceReconcilerTest {

    @Test
    void wantedConnectedDeviceWithUnresolvedGattResolvesServicesWithoutDisconnecting() {
        TestPort port = new TestPort();
        port.wanted = true;
        port.hasNative = true;
        port.nativeConnected = true;
        port.flagConnected = true;
        port.gattResolved = false;

        DeviceReconciler reconciler = reconciler(port);

        reconciler.reconcile();

        assertEquals(1, port.resolveGattCalls);
        assertEquals(0, port.disconnectNativeCalls);
        assertEquals(0, port.markDisconnectedCalls);
    }

    @Test
    void unwantedConnectedDeviceIsDisconnected() {
        TestPort port = new TestPort();
        port.wanted = false;
        port.hasNative = true;
        port.nativeConnected = true;
        port.flagConnected = true;
        port.gattResolved = true;

        DeviceReconciler reconciler = reconciler(port);

        reconciler.reconcile();

        assertEquals(1, port.disconnectNativeCalls);
        assertEquals(1, port.markDisconnectedCalls);
        assertEquals(0, port.resolveGattCalls);
    }

    @Test
    void unwantedConnectingDeviceIsMarkedDisconnected() {
        TestPort port = new TestPort();
        port.wanted = false;
        port.hasNative = true;
        port.flagConnecting = true;

        DeviceReconciler reconciler = reconciler(port);

        reconciler.reconcile();

        assertEquals(1, port.markDisconnectedCalls);
        assertEquals(0, port.disconnectNativeCalls);
        assertEquals(0, port.resolveGattCalls);
    }

    private static DeviceReconciler reconciler(TestPort port) {
        return new DeviceReconciler(LoggerFactory.getLogger(DeviceReconcilerTest.class), port, () -> true,
                new ResetBudget(0), () -> {
                });
    }

    private static final class TestPort implements DevicePort {
        boolean wanted;
        boolean hasNative;
        boolean nativeConnected;
        boolean gattResolved;
        boolean flagConnected;
        boolean flagConnecting;

        int disconnectNativeCalls;
        int markDisconnectedCalls;
        int resolveGattCalls;

        @Override
        public boolean isWanted() {
            return wanted;
        }

        @Override
        public boolean hasNativeDevice() {
            return hasNative;
        }

        @Override
        public boolean isNativeConnected() {
            return nativeConnected;
        }

        @Override
        public boolean isGattResolved() {
            return gattResolved;
        }

        @Override
        public boolean isFlagConnected() {
            return flagConnected;
        }

        @Override
        public boolean isFlagConnecting() {
            return flagConnecting;
        }

        @Override
        public void markConnected() {
            flagConnected = true;
            flagConnecting = false;
        }

        @Override
        public void markDisconnected() {
            markDisconnectedCalls++;
            nativeConnected = false;
            flagConnected = false;
            flagConnecting = false;
        }

        @Override
        public void markConnecting() {
            flagConnecting = true;
        }

        @Override
        public HCIStatusCode connectNative() {
            return HCIStatusCode.SUCCESS;
        }

        @Override
        public void disconnectNative() {
            disconnectNativeCalls++;
            nativeConnected = false;
        }

        @Override
        public void resolveGatt() {
            resolveGattCalls++;
            gattResolved = true;
        }

        @Override
        public String id() {
            return "test-device";
        }
    }
}
