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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.direct_bt.BDAddressAndType;
import org.direct_bt.BDAddressType;
import org.direct_bt.BTAdapter;
import org.direct_bt.BTDevice;
import org.direct_bt.BTMode;
import org.direct_bt.GattCacheMode;
import org.direct_bt.HCIStatusCode;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.jau.net.EUI48;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openhab.binding.bluetooth.BluetoothAddress;
import org.openhab.binding.bluetooth.BluetoothBindingConstants;
import org.openhab.binding.bluetooth.BluetoothDevice;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.MutableClock;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.ResetBudget;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.Thing;
import org.slf4j.LoggerFactory;

/**
 * Regression harness for the {@link DirectBTBridgeHandler} pieces the encryption feature depends on — the
 * per-device security config lookups (mode + passkey, matched to the child Thing by address), the SMP
 * passkey reply, and the orphan-adoption matcher — plus the edge-triggered adapter power-up ladder
 * ({@code powerUpAdapter}), which must stay behaviourally in lockstep with {@code AdapterReconciler}'s
 * level-triggered copy until the two are folded together. Everything is exercised with mocked Things /
 * native devices — no controller, no OSGi.
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
    void gattCacheModeIsReadFromTheMatchingChildThing() {
        childThings(childThing(DEVICE_ADDR, Map.of("gattCache", "off")));
        assertEquals(GattCacheMode.OFF, handler().getDeviceGattCacheMode(ADDRESS));

        childThings(childThing(DEVICE_ADDR, Map.of("gattCache", "trust")));
        assertEquals(GattCacheMode.TRUST, handler().getDeviceGattCacheMode(ADDRESS));

        childThings(childThing(DEVICE_ADDR, Map.of("gattCache", "auto")));
        assertEquals(GattCacheMode.AUTO, handler().getDeviceGattCacheMode(ADDRESS));
    }

    @Test
    void gattCacheModeDefaultsToAutoWhenUnsetUnknownOrNoChildThing() {
        childThings(childThing(DEVICE_ADDR, Map.of()));
        assertEquals(GattCacheMode.AUTO, handler().getDeviceGattCacheMode(ADDRESS));

        childThings(childThing(DEVICE_ADDR, Map.of("gattCache", "future-mode")));
        assertEquals(GattCacheMode.AUTO, handler().getDeviceGattCacheMode(ADDRESS));

        childThings(childThing("11:22:33:44:55:66", Map.of("gattCache", "off")));
        assertEquals(GattCacheMode.AUTO, handler().getDeviceGattCacheMode(ADDRESS));
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

    @Test
    void deviceFoundDoesNotBlockOnDiscoveryFanout() throws Exception {
        CountDownLatch discoveryEntered = new CountDownLatch(1);
        CountDownLatch releaseDiscovery = new CountDownLatch(1);
        CountDownLatch discoveryCompleted = new CountDownLatch(1);
        DirectBTBridgeHandler h = new DirectBTBridgeHandler(bridgeThing(), managerFactory(), new MutableClock(0)) {
            @Override
            public void deviceDiscovered(BluetoothDevice device) {
                discoveryEntered.countDown();
                try {
                    releaseDiscovery.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                discoveryCompleted.countDown();
            }
        };
        BTDevice nativeDevice = nativeDevice(DEVICE_ADDR);

        DirectBTBluetoothDevice wrapper = h.handleDeviceFound(nativeDevice);

        try {
            assertNotNull(wrapper);
            assertSame(nativeDevice, wrapper.getBTDevice(), "native handle update must happen before callback return");
            assertTrue(discoveryEntered.await(1, TimeUnit.SECONDS), "discovery fanout should still run asynchronously");
            assertFalse(discoveryCompleted.await(100, TimeUnit.MILLISECONDS),
                    "discovery fanout should still be blocked while deviceFound has already returned");
        } finally {
            releaseDiscovery.countDown();
        }
        assertTrue(discoveryCompleted.await(1, TimeUnit.SECONDS));
    }

    @Test
    void bridgeExposesDeviceActorDiagnosticSnapshot() {
        BTDevice nativeDevice = nativeDevice(DEVICE_ADDR);
        DirectBTBluetoothDevice device = handler().handleDeviceFound(nativeDevice);
        assertNotNull(device);

        Map<String, String> diagnostics = handler().getDeviceActorDiagnosticSummaries();
        String summary = diagnostics.get(DEVICE_ADDR);

        assertSame(device, handler().getDevice(ADDRESS));
        assertEquals(1, diagnostics.size());
        assertTrue(diagnostics.containsKey(DEVICE_ADDR));
        assertNotNull(summary);
        assertTrue(summary.contains("state=IDLE_DISABLED"));
    }

    // --- adapter power-up ladder (powerUpAdapter) ---------------------------------------------------
    // The edge-triggered bring-up copy of the power ladder. These tests lock down the decisions that were
    // tuned against live CSR/Realtek controllers, mirroring AdapterReconcilerTest's coverage of the
    // level-triggered copy in act()/escalate(): the two ladders must not drift apart until folded together.

    /** Short bounded wait so the "never powers on" case fails in milliseconds, not the production 2s. */
    private static final int WAIT_TRIES = 3;
    private static final long WAIT_MS = 1;

    private static @Nullable String powerUp(BTAdapter a) throws InterruptedException {
        return DirectBTBridgeHandler.powerUpAdapter(a, LoggerFactory.getLogger("test"), WAIT_TRIES, WAIT_MS);
    }

    @Test
    void powerUpDoesNothingWhenAlreadyInitializedAndPowered() throws InterruptedException {
        BTAdapter a = mock(BTAdapter.class);
        when(a.isInitialized()).thenReturn(true);
        when(a.isPowered()).thenReturn(true);

        assertNull(powerUp(a), "already up: success with no commands");
        verify(a, never()).initialize(any(), anyBoolean());
        verify(a, never()).setPowered(anyBoolean());
        verify(a, never()).reset();
    }

    @Test
    void powerUpInitializesWithPowerOnAsASingleStep() throws InterruptedException {
        // The CSR8510 regression: initialize(...,false) followed by a separate power step stalled bring-up
        // (initialize left powered=false and the follow-up reset() hung). Power-on MUST ride along with
        // initialize as one native call.
        BTAdapter a = mock(BTAdapter.class);
        when(a.isInitialized()).thenReturn(false);
        when(a.initialize(any(), anyBoolean())).thenReturn(HCIStatusCode.SUCCESS);
        when(a.isPowered()).thenReturn(true); // initialize powered it on

        assertNull(powerUp(a));
        verify(a).initialize(BTMode.DUAL, true);
        verify(a, never()).reset();
        verify(a, never()).setPowered(anyBoolean());
    }

    @Test
    void powerUpFailsClosedWhenInitializeFails() throws InterruptedException {
        BTAdapter a = mock(BTAdapter.class);
        when(a.isInitialized()).thenReturn(false);
        when(a.initialize(any(), anyBoolean())).thenReturn(HCIStatusCode.INTERNAL_FAILURE);

        String error = powerUp(a);
        assertNotNull(error, "a failed initialize must fail the bring-up");
        assertTrue(error.contains("initialization failed"), error);
        // No fallback poking on a controller that refused initialize — a blind reset() can wedge CSR.
        verify(a, never()).reset();
        verify(a, never()).setPowered(anyBoolean());
    }

    @Test
    void powerUpTriesSetPoweredBeforeReset() throws InterruptedException {
        // Initialized but off: setPowered(true) is the gentle path; reset() must NOT run when it takes.
        BTAdapter a = mock(BTAdapter.class);
        when(a.isInitialized()).thenReturn(true);
        when(a.isPowered()).thenReturn(false, true); // off at the gate, on after setPowered
        when(a.setPowered(true)).thenReturn(true);

        assertNull(powerUp(a));
        verify(a).setPowered(true);
        verify(a, never()).reset(); // a blind reset() can hang/wedge some CSR controllers
    }

    @Test
    void powerUpFallsBackToResetOnlyWhenSetPoweredFails() throws InterruptedException {
        BTAdapter a = mock(BTAdapter.class);
        when(a.isInitialized()).thenReturn(true);
        // off at the gate, still off right after reset (so the ladder must setPowered again), then on.
        // (the post-reset debug log also reads isPowered once, hence the extra false)
        when(a.isPowered()).thenReturn(false, false, false, true);
        when(a.setPowered(true)).thenReturn(false, true);
        when(a.reset()).thenReturn(HCIStatusCode.SUCCESS);

        assertNull(powerUp(a));
        InOrder inOrder = inOrder(a);
        inOrder.verify(a).setPowered(true); // gentle path first
        inOrder.verify(a).reset(); // fallback only after it failed
        inOrder.verify(a).setPowered(true); // reset succeeded but left the adapter off -> power it
    }

    @Test
    void powerUpReportsFailureWhenResetFails() throws InterruptedException {
        BTAdapter a = mock(BTAdapter.class);
        when(a.isInitialized()).thenReturn(true);
        when(a.isPowered()).thenReturn(false);
        when(a.setPowered(true)).thenReturn(false);
        when(a.reset()).thenReturn(HCIStatusCode.TIMEOUT);

        String error = powerUp(a);
        assertNotNull(error);
        assertTrue(error.contains("power-up failed"), error);
    }

    @Test
    void powerUpWaitsBoundedForAsynchronousPowerOn() throws InterruptedException {
        // Power-on can complete asynchronously after the command returns; the ladder must poll (bounded)
        // until the controller reports POWERED before the caller attaches listeners / starts discovery.
        BTAdapter a = mock(BTAdapter.class);
        when(a.isInitialized()).thenReturn(true);
        when(a.setPowered(true)).thenReturn(true);
        when(a.isPowered()).thenReturn(false, false, true); // off at the gate, off on first poll, then on

        assertNull(powerUp(a), "success once the controller reports POWERED within the wait budget");
    }

    @Test
    void powerUpFailsWhenTheControllerNeverReportsPowered() throws InterruptedException {
        BTAdapter a = mock(BTAdapter.class);
        when(a.isInitialized()).thenReturn(true);
        when(a.setPowered(true)).thenReturn(true);
        when(a.isPowered()).thenReturn(false); // never comes up

        String error = powerUp(a);
        assertNotNull(error, "the wait is bounded; a controller that never powers must fail the bring-up");
        assertTrue(error.contains("did not power on"), error);
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
