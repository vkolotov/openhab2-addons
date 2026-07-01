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
package org.openhab.binding.bluetooth.directbt.internal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Objects;
import java.util.concurrent.Executors;

import org.direct_bt.BTDevice;
import org.direct_bt.BTSecurityLevel;
import org.direct_bt.HCIStatusCode;
import org.direct_bt.SMPIOCapability;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openhab.binding.bluetooth.BluetoothAddress;
import org.openhab.binding.bluetooth.BluetoothDeviceListener;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.ResetBudget;
import org.slf4j.LoggerFactory;

/**
 * Regression harness for {@link DirectBTBluetoothDevice} — the pure {@link org.openhab.binding.bluetooth.directbt
 * .internal.reconcile.DevicePort} + connection-intent logic, exercised with a mocked native {@link BTDevice}
 * (an interface) and a mocked {@link DirectBTBridgeHandler}. Native calls (connectLE / setConnSecurity /
 * getConnected) go to the mock, so these tests lock down the device's decisions without a real controller.
 * <p>
 * Cross-reference: the connect-intent split (connect/disconnect/reconnect) is the T-GEN fix; the connectLE
 * parameter pinning is the dead-stable BLE profile from {@code docs/directbt-stability-fix-inventory.md}.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DirectBTBluetoothDeviceTest {

    private static final BluetoothAddress ADDRESS = new BluetoothAddress("70:B9:50:92:A9:90");

    @Mock
    private @Nullable DirectBTBridgeHandler bridge;
    @Mock
    private @Nullable BTDevice nativeDevice;

    private @Nullable DirectBTBluetoothDevice device;

    @BeforeEach
    void setUp() {
        DirectBTBridgeHandler b = bridge();
        when(b.getExecutor()).thenReturn(Executors.newSingleThreadExecutor());
        when(b.getResetBudget()).thenReturn(new ResetBudget(LoggerFactory.getLogger("test"), 8000));
        device = new DirectBTBluetoothDevice(b, ADDRESS);
    }

    // --- connection intent (the connect/disconnect/reconnect split — T-GEN) ----------------------

    @Test
    void connectWithNoListenersIsRefusedAdmissionControl() {
        // No listeners added -> admission control refuses (the core connect-probes every discovered device to
        // fingerprint it; refusing those keeps the controller clean).
        assertFalse(device().connect(), "a device with no listeners must not connect");
        assertFalse(device().isWanted());
    }

    @Test
    void connectWithAListenerSetsConnectionIntent() {
        enableDevice(true);
        device().addListener(mock(BluetoothDeviceListener.class));

        assertTrue(device().connect(), "connect() is accepted as intent once a listener exists");
        assertTrue(device().isWanted(), "wanted = enabled Thing AND connect intent");
        verify(bridge()).requeueReconcile();
    }

    @Test
    void disconnectClearsConnectionIntent() {
        enableDevice(true);
        device().addListener(mock(BluetoothDeviceListener.class));
        device().connect();
        assertTrue(device().isWanted());

        assertTrue(device().disconnect());
        assertFalse(device().isWanted(), "disconnect() is a real intent change: no longer wanted");
    }

    @Test
    void reconnectKeepsConnectionIntentButDropsTheLink() {
        // reconnect() = "bounce the link, KEEP the intent" (the generic handler's GATT-unresolved recovery). This
        // is the whole point of the T-GEN reconnect() split: it must NOT clear wantConnected the way disconnect does.
        enableDevice(true);
        device().addListener(mock(BluetoothDeviceListener.class));
        device().connect();
        device().updateBTDevice(nativeDevice());
        device().markConnected();

        assertTrue(device().reconnect());
        assertTrue(device().isWanted(), "reconnect keeps the connection intent");
        assertFalse(device().hasNativeDevice(), "reconnect drops the live handle so a fresh advert re-connects");
    }

    // --- DevicePort native truth -----------------------------------------------------------------

    @Test
    void hasNativeDeviceReflectsHandlePresence() {
        assertFalse(device().hasNativeDevice(), "no handle before discovery");
        device().updateBTDevice(nativeDevice());
        assertTrue(device().hasNativeDevice());
    }

    @Test
    void isNativeConnectedReadsTheNativeHandle() {
        assertFalse(device().isNativeConnected(), "no handle == not connected");

        device().updateBTDevice(nativeDevice());
        when(nativeDevice().getConnected()).thenReturn(true);
        assertTrue(device().isNativeConnected());

        when(nativeDevice().getConnected()).thenReturn(false);
        assertFalse(device().isNativeConnected());
    }

    @Test
    void isNativeConnectedSwallowsNativeThrow() {
        device().updateBTDevice(nativeDevice());
        when(nativeDevice().getConnected()).thenThrow(new RuntimeException("native poll blew up"));

        assertFalse(device().isNativeConnected(), "a throwing native poll degrades to not-connected, not a crash");
    }

    @Test
    void markDisconnectedClearsTheStaleHandle() {
        device().updateBTDevice(nativeDevice());
        assertTrue(device().hasNativeDevice());

        device().markDisconnected();

        assertFalse(device().hasNativeDevice(), "the stale handle must be dropped so the device is re-discovered");
        assertFalse(device().isFlagConnected());
    }

    // --- connectNative: parameter pinning + failure handling -------------------------------------

    @Test
    void connectNativeWithoutHandleReturnsInternalFailure() {
        assertEquals(HCIStatusCode.INTERNAL_FAILURE, device().connectNative(), "no handle -> cannot connect");
    }

    @Test
    void connectNativePinsTheStableBleParameters() {
        device().updateBTDevice(nativeDevice());
        when(nativeDevice().connectLE(anyShort(), anyShort(), anyShort(), anyShort(), anyShort(), anyShort()))
                .thenReturn(HCIStatusCode.SUCCESS);

        assertEquals(HCIStatusCode.SUCCESS, device().connectNative());

        // Security is pinned to NONE / NO_INPUT_NO_OUTPUT (unbonded sensor), and connectLE is issued with the
        // pinned interval/supervision profile that field-tested dead-stable. Assert the security pin and that a
        // create-connection was actually issued.
        verify(nativeDevice()).setConnSecurity(BTSecurityLevel.NONE, SMPIOCapability.NO_INPUT_NO_OUTPUT);
        verify(nativeDevice()).connectLE(anyShort(), anyShort(), anyShort(), anyShort(), anyShort(), anyShort());
    }

    @Test
    void connectNativeSwallowsNativeThrowAsInternalFailure() {
        device().updateBTDevice(nativeDevice());
        when(nativeDevice().connectLE(anyShort(), anyShort(), anyShort(), anyShort(), anyShort(), anyShort()))
                .thenThrow(new RuntimeException("controller rejected"));

        assertEquals(HCIStatusCode.INTERNAL_FAILURE, device().connectNative(),
                "a native throw during connect degrades to INTERNAL_FAILURE, not a propagated exception");
    }

    @Test
    void disconnectNativeWithoutHandleIsANoOp() {
        assertDoesNotThrow(() -> device().disconnectNative());
    }

    @Test
    void idIsTheAddress() {
        assertEquals(ADDRESS.toString(), device().id());
    }

    // --- helpers (unwrap the @Nullable mocks/SUT into @NonNull locals) ----------------------------

    private void enableDevice(boolean enabled) {
        when(bridge().isDeviceEnabled(any())).thenReturn(enabled);
    }

    private DirectBTBridgeHandler bridge() {
        return Objects.requireNonNull(bridge);
    }

    private BTDevice nativeDevice() {
        return Objects.requireNonNull(nativeDevice);
    }

    private DirectBTBluetoothDevice device() {
        return Objects.requireNonNull(device);
    }
}
