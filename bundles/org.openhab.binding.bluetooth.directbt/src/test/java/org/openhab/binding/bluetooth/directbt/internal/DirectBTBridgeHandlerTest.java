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
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;

import org.direct_bt.BDAddressAndType;
import org.direct_bt.BDAddressType;
import org.direct_bt.BTDevice;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.jau.net.EUI48;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openhab.binding.bluetooth.BluetoothAddress;
import org.openhab.binding.bluetooth.BluetoothBindingConstants;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.MutableClock;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.ResetBudget;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.Thing;
import org.slf4j.LoggerFactory;

/**
 * Regression harness for the {@link DirectBTBridgeHandler} pieces the encryption feature depends on: the
 * per-device security config lookups (mode + passkey, matched to the child Thing by address), the SMP
 * passkey reply, and the orphan-adoption matcher. Everything is exercised with mocked Things / native
 * devices — no controller, no OSGi.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DirectBTBridgeHandlerTest {

    private static final String DEVICE_ADDR = "70:B9:50:92:A9:90";
    private static final BluetoothAddress ADDRESS = new BluetoothAddress(DEVICE_ADDR);

    @Mock
    private @Nullable Bridge bridgeThing;
    @Mock
    private @Nullable DirectBTManagerFactory managerFactory;

    private @Nullable DirectBTBridgeHandler handler;

    @BeforeEach
    void setUp() {
        handler = new DirectBTBridgeHandler(bridgeThing(), managerFactory(), new MutableClock(0));
    }

    // --- per-device security config lookups (matched to the child Thing by address) ---------------

    @Test
    void connectionSecurityIsReadFromTheMatchingChildThing() {
        childThings(childThing(DEVICE_ADDR, Map.of("connectionSecurity", "pin")));

        assertEquals("pin", handler().getDeviceConnectionSecurity(ADDRESS));
    }

    @Test
    void connectionSecurityAddressMatchIsCaseInsensitive() {
        childThings(childThing(DEVICE_ADDR.toLowerCase(Locale.ROOT), Map.of("connectionSecurity", "encrypted")));

        assertEquals("encrypted", handler().getDeviceConnectionSecurity(ADDRESS));
    }

    @Test
    void connectionSecurityDefaultsToNoneWhenUnset() {
        childThings(childThing(DEVICE_ADDR, Map.of()));

        assertEquals(BluetoothBindingConstants.CONNECTION_SECURITY_NONE,
                handler().getDeviceConnectionSecurity(ADDRESS));
    }

    @Test
    void connectionSecurityDefaultsToNoneWhenBlankOrNoChildThing() {
        childThings(childThing(DEVICE_ADDR, Map.of("connectionSecurity", "  ")));
        assertEquals(BluetoothBindingConstants.CONNECTION_SECURITY_NONE, handler().getDeviceConnectionSecurity(ADDRESS),
                "a blank value must not select a mode");

        childThings(childThing("11:22:33:44:55:66", Map.of("connectionSecurity", "pin")));
        assertEquals(BluetoothBindingConstants.CONNECTION_SECURITY_NONE, handler().getDeviceConnectionSecurity(ADDRESS),
                "an unknown device (no child Thing with this address) must default to none");
    }

    @Test
    void passkeyIsReadAsNumberOrNumericString() {
        // The REST/UI layer stores numbers as BigDecimal; a .things file may yield a String. Both must parse.
        childThings(childThing(DEVICE_ADDR, Map.of("passkey", new BigDecimal(123456))));
        assertEquals(123456, handler().getDevicePasskey(ADDRESS));

        childThings(childThing(DEVICE_ADDR, Map.of("passkey", "042")));
        assertEquals(42, handler().getDevicePasskey(ADDRESS));
    }

    @Test
    void passkeyIsMinusOneWhenMissingOrInvalid() {
        childThings(childThing(DEVICE_ADDR, Map.of()));
        assertEquals(-1, handler().getDevicePasskey(ADDRESS), "no passkey configured");

        childThings(childThing(DEVICE_ADDR, Map.of("passkey", "not-a-pin")));
        assertEquals(-1, handler().getDevicePasskey(ADDRESS), "an unparseable passkey must be ignored, not crash");
    }

    // --- SMP passkey reply (PASSKEY_EXPECTED) ------------------------------------------------------

    @Test
    void replyPasskeySuppliesTheConfiguredPin() {
        childThings(childThing(DEVICE_ADDR, Map.of("passkey", new BigDecimal(123456))));
        BTDevice nativeDevice = nativeDevice(DEVICE_ADDR);

        handler().replyPasskey(nativeDevice);

        verify(nativeDevice).setPairingPasskey(123456);
        verify(nativeDevice, never()).setPairingPasskeyNegative();
    }

    @Test
    void replyPasskeyDeclinesWhenNoPinConfigured() {
        // Without a configured passkey the negotiation must be declined cleanly (negative reply), not left
        // hanging until the SMP timeout.
        childThings(childThing(DEVICE_ADDR, Map.of()));
        BTDevice nativeDevice = nativeDevice(DEVICE_ADDR);

        handler().replyPasskey(nativeDevice);

        verify(nativeDevice).setPairingPasskeyNegative();
        verify(nativeDevice, never()).setPairingPasskey(anyInt());
    }

    @Test
    void replyPasskeySwallowsNativeThrow() {
        childThings(childThing(DEVICE_ADDR, Map.of("passkey", new BigDecimal(123456))));
        BTDevice nativeDevice = nativeDevice(DEVICE_ADDR);
        when(nativeDevice.setPairingPasskey(anyInt())).thenThrow(new RuntimeException("SMP state changed"));

        assertDoesNotThrow(() -> handler().replyPasskey(nativeDevice), "a native throw must not kill the callback");
    }

    // --- orphan-adoption matcher (level-triggered re-attach of a connected native device) ----------

    @Test
    void adoptionReattachesAConnectedNativeDeviceWithTheOrphansAddress() {
        DirectBTBluetoothDevice orphan = orphanWrapper();
        BTDevice candidate = nativeDevice(DEVICE_ADDR);
        when(candidate.getConnected()).thenReturn(true);

        DirectBTBridgeHandler.adoptMatchingOrphans(List.of(orphan), List.of(candidate),
                LoggerFactory.getLogger("test"));

        assertSame(candidate, orphan.getBTDevice(), "the connected native device must be re-adopted as the handle");
    }

    @Test
    void adoptionIgnoresADisconnectedCandidate() {
        // A disconnected native device must come back through a fresh advertisement (deviceFound), preserving
        // the "trust the fresh frame, not a cached object" discipline — adoption is only for live links.
        DirectBTBluetoothDevice orphan = orphanWrapper();
        BTDevice candidate = nativeDevice(DEVICE_ADDR);
        when(candidate.getConnected()).thenReturn(false);

        DirectBTBridgeHandler.adoptMatchingOrphans(List.of(orphan), List.of(candidate),
                LoggerFactory.getLogger("test"));

        assertNull(orphan.getBTDevice(), "a disconnected candidate must NOT be adopted");
    }

    @Test
    void adoptionIgnoresACandidateWithADifferentAddress() {
        DirectBTBluetoothDevice orphan = orphanWrapper();
        BTDevice candidate = nativeDevice("11:22:33:44:55:66");
        when(candidate.getConnected()).thenReturn(true);

        DirectBTBridgeHandler.adoptMatchingOrphans(List.of(orphan), List.of(candidate),
                LoggerFactory.getLogger("test"));

        assertNull(orphan.getBTDevice());
    }

    @Test
    void adoptionSkipsAThrowingCandidateAndContinues() {
        DirectBTBluetoothDevice orphan = orphanWrapper();
        BTDevice broken = mock(BTDevice.class);
        when(broken.getAddressAndType()).thenThrow(new RuntimeException("native object gone"));
        BTDevice good = nativeDevice(DEVICE_ADDR);
        when(good.getConnected()).thenReturn(true);

        assertDoesNotThrow(() -> DirectBTBridgeHandler.adoptMatchingOrphans(List.of(orphan), List.of(broken, good),
                LoggerFactory.getLogger("test")));

        assertSame(good, orphan.getBTDevice(), "one broken candidate must not prevent adopting the good one");
    }

    // --- helpers -----------------------------------------------------------------------------------

    private void childThings(Thing... things) {
        when(bridgeThing().getThings()).thenReturn(List.of(things));
    }

    private static Thing childThing(String address, Map<String, Object> extraConfig) {
        Thing thing = mock(Thing.class);
        java.util.HashMap<String, Object> config = new java.util.HashMap<>(extraConfig);
        config.put("address", address);
        when(thing.getConfiguration()).thenReturn(new Configuration(config));
        return thing;
    }

    /** A mocked native device reporting the given address (RANDOM type; the matcher ignores the type). */
    private static BTDevice nativeDevice(String address) {
        BTDevice dev = mock(BTDevice.class);
        when(dev.getAddressAndType())
                .thenReturn(new BDAddressAndType(new EUI48(address), BDAddressType.BDADDR_LE_RANDOM));
        return dev;
    }

    /** A real wrapper with no native handle — the orphan side of the adoption matcher. */
    private DirectBTBluetoothDevice orphanWrapper() {
        DirectBTBridgeHandler b = mock(DirectBTBridgeHandler.class);
        when(b.getExecutor()).thenReturn(Executors.newSingleThreadExecutor());
        when(b.getResetBudget()).thenReturn(new ResetBudget(8000));
        return new DirectBTBluetoothDevice(b, ADDRESS);
    }

    private Bridge bridgeThing() {
        Bridge b = bridgeThing;
        assertNotNull(b);
        return b;
    }

    private DirectBTManagerFactory managerFactory() {
        DirectBTManagerFactory f = managerFactory;
        assertNotNull(f);
        return f;
    }

    private DirectBTBridgeHandler handler() {
        DirectBTBridgeHandler h = handler;
        assertNotNull(h);
        return h;
    }
}
