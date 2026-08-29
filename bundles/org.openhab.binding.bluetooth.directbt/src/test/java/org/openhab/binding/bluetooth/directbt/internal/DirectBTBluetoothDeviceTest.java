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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.direct_bt.BTDevice;
import org.direct_bt.BTGattChar;
import org.direct_bt.BTGattCharListener;
import org.direct_bt.BTGattService;
import org.direct_bt.BTSecurityLevel;
import org.direct_bt.GattCharPropertySet;
import org.direct_bt.HCIStatusCode;
import org.direct_bt.PairingMode;
import org.direct_bt.SMPIOCapability;
import org.direct_bt.SMPPairingState;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openhab.binding.bluetooth.BluetoothAddress;
import org.openhab.binding.bluetooth.BluetoothBindingConstants;
import org.openhab.binding.bluetooth.BluetoothCharacteristic;
import org.openhab.binding.bluetooth.BluetoothDeviceListener;
import org.openhab.binding.bluetooth.BluetoothService;
import org.openhab.binding.bluetooth.directbt.internal.metrics.BluetoothMetrics;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.MutableClock;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.ResetBudget;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.device.DeviceActorDiagnostics;

/**
 * Regression harness for {@link DirectBTBluetoothDevice} — the pure {@link org.openhab.binding.bluetooth.directbt
 * .internal.reconcile.DevicePort} + connection-intent logic, exercised with a mocked native {@link BTDevice}
 * (an interface) and a mocked {@link DirectBTBridgeHandler}. Native calls (connectLE / setConnSecurity /
 * getConnected) go to the mock, so these tests lock down the device's decisions without a real controller.
 * <p>
 * The connectLE parameter pinning locks down the dead-stable BLE profile validated against live hardware
 * (see the constants in {@link DirectBTBluetoothDevice}).
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DirectBTBluetoothDeviceTest {

    private static final BluetoothAddress ADDRESS = new BluetoothAddress("11:22:33:44:55:66");
    private static final UUID SERVICE_UUID = UUID.fromString("9f0d7d29-8816-4215-bd7f-2e2a264f0891");
    private static final UUID SECOND_SERVICE_UUID = UUID.fromString("9f0d7d2a-8816-4215-bd7f-2e2a264f0891");
    private static final UUID CHAR_UUID = UUID.fromString("9f0dd907-8816-4215-bd7f-2e2a264f0891");

    @Mock
    private @Nullable DirectBTBridgeHandler bridge;
    @Mock
    private @Nullable BTDevice nativeDevice;

    private @Nullable DirectBTBluetoothDevice device;

    @BeforeEach
    void setUp() {
        DirectBTBridgeHandler b = bridge();
        when(b.getExecutor()).thenReturn(Executors.newSingleThreadExecutor());
        // Multi-threaded on purpose: the per-device SerialExecutor must impose ordering, not the pool.
        when(b.getNotifyExecutor()).thenReturn(Executors.newFixedThreadPool(4));
        when(b.getResetBudget()).thenReturn(new ResetBudget(8000));
        // Metrics publish into a registry-less BluetoothMetrics here: the meters are exercised (so a broken
        // instrumentation call still fails the test) but nothing is exported.
        when(b.createDeviceMetrics(any()))
                .thenReturn(new BluetoothMetrics().forDevice("test", ADDRESS.toString(), "bluetooth:directbt:test"));
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
    void actorDiagnosticsAreExposedThroughDevice() {
        enableDevice(true);
        device().addListener(mock(BluetoothDeviceListener.class));
        device().connect();

        device().getReconciler().reconcile();

        DeviceActorDiagnostics diagnostics = device().getActorDiagnostics();
        assertEquals("DISCOVERING", diagnostics.stateName());
        assertEquals("NATIVE_HANDLE", diagnostics.waitingOnName());
        assertTrue(diagnostics.summary().contains("state=DISCOVERING"));
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

    @Test
    void reconnectWithinGraceOfConnectIsRefusedWhileLinkIsUp() {
        // The generic handler may request a bounce the moment it sees CONNECTED with services unresolved, but the
        // reconciler's post-connect GATT resolve can outlast one poll period. Within the grace window the bounce
        // must be refused (the in-flight resolve IS the recovery), else every fresh connection gets torn down
        // mid-resolve and the device loops connect/bounce forever.
        MutableClock clock = new MutableClock(1_000_000);
        DirectBTBluetoothDevice dev = new DirectBTBluetoothDevice(bridge(), ADDRESS, clock);
        dev.updateBTDevice(nativeDevice());
        when(nativeDevice().getConnected()).thenReturn(true);
        dev.markConnected();

        clock.advance(DirectBTBluetoothDevice.RECONNECT_GRACE_MILLIS - 1);
        assertTrue(dev.reconnect());

        assertTrue(dev.hasNativeDevice(), "bounce within the grace window is refused; the handle stays");
        assertTrue(dev.isFlagConnected(), "the connection is untouched while the resolve is in flight");
        verify(nativeDevice(), never()).disconnect();
    }

    @Test
    void reconnectAfterGraceIsHonoured() {
        MutableClock clock = new MutableClock(1_000_000);
        DirectBTBluetoothDevice dev = new DirectBTBluetoothDevice(bridge(), ADDRESS, clock);
        dev.updateBTDevice(nativeDevice());
        when(nativeDevice().getConnected()).thenReturn(true);
        dev.markConnected();

        clock.advance(DirectBTBluetoothDevice.RECONNECT_GRACE_MILLIS + 1);
        assertTrue(dev.reconnect());

        // Past the grace an unresolved link is genuinely stuck: the bounce proceeds (and tears the link down).
        assertFalse(dev.hasNativeDevice(), "after the grace the bounce drops the handle for a fresh re-connect");
    }

    @Test
    void reconnectIsRefusedWhileSmpPairingIsNegotiatingEvenPastTheGrace() {
        // An authenticated (passkey) SMP negotiation can stall and retry internally for tens of seconds with
        // GATT unresolved throughout. The bounce must never pre-empt an in-flight SMP — same freeze discipline
        // as the reconciler's pairing-aware connect deadline.
        MutableClock clock = new MutableClock(1_000_000);
        DirectBTBluetoothDevice dev = new DirectBTBluetoothDevice(bridge(), ADDRESS, clock);
        dev.updateBTDevice(nativeDevice());
        when(nativeDevice().getConnected()).thenReturn(true);
        when(nativeDevice().getPairingState()).thenReturn(SMPPairingState.PASSKEY_EXPECTED);
        dev.markConnected();

        clock.advance(DirectBTBluetoothDevice.RECONNECT_GRACE_MILLIS + 1); // grace alone would allow the bounce
        assertTrue(dev.reconnect());

        assertTrue(dev.hasNativeDevice(), "a bounce must never tear down a link mid-SMP");
        verify(nativeDevice(), never()).disconnect();
    }

    @Test
    void discoverServicesIsDeferredWhileActorIsSettlingFreshLink() {
        // The generic Bluetooth handler calls discoverServices() immediately on CONNECTED when services are still
        // unresolved. Direct-BT must not let that bypass the actor-owned settle window; early native GATT walks
        // were observed live as instant empty service models and fast HP disconnects.
        device().updateBTDevice(nativeDevice());
        when(nativeDevice().getConnected()).thenReturn(true);
        device().markConnected();

        assertFalse(device().discoverServices(), "upper-layer discovery is deferred during link settle");
        verify(nativeDevice(), never()).getGattServices();
        verify(bridge()).requeueReconcile();
    }

    @Test
    void adoptionIsSuppressedDuringTheQuietWindowAfterATeardown() {
        // A deliberate teardown issues an ASYNC native disconnect; for a short window the native device still
        // reads connected. The adoption sweep must not re-attach it in that window (it would resurrect the link
        // the bounce just chose to drop), and must be allowed again once the window passes.
        MutableClock clock = new MutableClock(1_000_000);
        DirectBTBluetoothDevice dev = new DirectBTBluetoothDevice(bridge(), ADDRESS, clock);
        dev.updateBTDevice(nativeDevice());

        assertTrue(dev.adoptionAllowed(), "adoption is allowed before any teardown");

        dev.markDisconnected();
        assertFalse(dev.adoptionAllowed(), "adoption is suppressed right after a teardown");

        clock.advance(DirectBTBluetoothDevice.ADOPTION_QUIET_MILLIS - 1);
        assertFalse(dev.adoptionAllowed(), "still suppressed inside the quiet window");

        clock.advance(2);
        assertTrue(dev.adoptionAllowed(), "allowed again once the quiet window has passed");
    }

    // --- markDisconnected() zombie-ACL guard ------------------------------------------------------

    @Test
    void markDisconnectedTearsDownALiveNativeLink() {
        device().updateBTDevice(nativeDevice());
        when(nativeDevice().getConnected()).thenReturn(true);

        device().markDisconnected();

        // Nulling the handle of a LIVE link would leak the ACL: the connected peripheral stops advertising and
        // rediscovery can never re-attach. The link must be torn down before the handle is dropped.
        verify(nativeDevice()).disconnect();
        assertFalse(device().hasNativeDevice());
    }

    @Test
    void markDisconnectedDisconnectsUnconditionally() {
        // getConnected() must NOT gate the teardown: an object adopted from an orphaned controller ACL can
        // report false while the controller still holds the link. Skipping the disconnect then leaks the ACL
        // permanently (the peripheral stays captive and never advertises). Disconnecting an actually-down
        // link is a cheap native no-op.
        device().updateBTDevice(nativeDevice());
        when(nativeDevice().getConnected()).thenReturn(false);

        device().markDisconnected();

        verify(nativeDevice()).disconnect();
        assertFalse(device().hasNativeDevice());
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
    void isNativeConnectedDistrustsStaleConnectedFlag() {
        // After a silent link drop Direct-BT's Java-side getConnected() can stay stale while the native
        // truth is gone. The flag is still trusted at this layer; the reconciler's resolve-fail streak
        // detects and recovers the stale case.
        device().updateBTDevice(nativeDevice());
        when(nativeDevice().getConnected()).thenReturn(true);
        assertTrue(device().isNativeConnected(),
                "the flag is trusted here; stale flags are detected by the reconciler's resolve-fail streak");
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
    void connectNativePinsUnbondedSecurityByDefault() {
        device().updateBTDevice(nativeDevice());
        when(nativeDevice().connectLE(anyShort(), anyShort(), anyShort(), anyShort(), anyShort(), anyShort()))
                .thenReturn(HCIStatusCode.SUCCESS);
        // Default (bridge returns "none"): security is pinned to NONE / NO_INPUT_NO_OUTPUT — the proven profile.
        when(bridge().getDeviceConnectionSecurity(any()))
                .thenReturn(BluetoothBindingConstants.CONNECTION_SECURITY_NONE);

        assertEquals(HCIStatusCode.SUCCESS, device().connectNative());

        verify(nativeDevice()).setConnSecurity(BTSecurityLevel.NONE, SMPIOCapability.NO_INPUT_NO_OUTPUT);
        verify(nativeDevice(), never()).setConnSecurityAuto(any());
        verify(nativeDevice()).connectLE(anyShort(), anyShort(), anyShort(), anyShort(), anyShort(), anyShort());
    }

    // --- persisted-bond wiring (restart survival) --------------------------------------------------

    @Test
    void connectNativeAppliesThePersistedBondOncePerHandle() {
        device().updateBTDevice(nativeDevice());
        when(nativeDevice().connectLE(anyShort(), anyShort(), anyShort(), anyShort(), anyShort(), anyShort()))
                .thenReturn(HCIStatusCode.SUCCESS);
        when(bridge().getDeviceConnectionSecurity(any()))
                .thenReturn(BluetoothBindingConstants.CONNECTION_SECURITY_ENCRYPTED);
        BondStore store = mock(BondStore.class);
        when(bridge().getBondStore()).thenReturn(store);

        device().connectNative();
        device().connectNative(); // retry on the same handle must not re-upload

        verify(store, times(1)).apply(nativeDevice());
    }

    @Test
    void connectNativeSkipsTheBondStoreForUnsecuredDevices() {
        device().updateBTDevice(nativeDevice());
        when(nativeDevice().connectLE(anyShort(), anyShort(), anyShort(), anyShort(), anyShort(), anyShort()))
                .thenReturn(HCIStatusCode.SUCCESS);
        when(bridge().getDeviceConnectionSecurity(any()))
                .thenReturn(BluetoothBindingConstants.CONNECTION_SECURITY_NONE);
        BondStore store = mock(BondStore.class);
        when(bridge().getBondStore()).thenReturn(store);

        device().connectNative();

        verify(store, never()).apply(any());
    }

    @Test
    void clearStalePairingAlsoDeletesThePersistedBond() {
        // The self-heal drops a dead in-memory key; the disk copy must go too, or every restart resurrects
        // the dead bond and the self-heal never sticks.
        BondStore store = mock(BondStore.class);
        when(bridge().getBondStore()).thenReturn(store);
        device().updateBTDevice(nativeDevice());

        device().clearStalePairing();

        verify(store).delete(ADDRESS);
        verify(nativeDevice()).unpair();
    }

    @Test
    void connectNativeRequestsJustWorksEncryptionWhenDeviceOptsIn() {
        device().updateBTDevice(nativeDevice());
        when(nativeDevice().connectLE(anyShort(), anyShort(), anyShort(), anyShort(), anyShort(), anyShort()))
                .thenReturn(HCIStatusCode.SUCCESS);
        // connectionSecurity=auto -> request Just-Works encryption via the EXPLICIT setConnSecurity(ENC_ONLY, ...).
        // NOT setConnSecurityAuto: that is a no-op in the adapter's Master (central) role, so the central-driven
        // explicit level is what actually takes. ENC_ONLY + NO_INPUT_NO_OUTPUT = encrypted, unauthenticated.
        when(bridge().getDeviceConnectionSecurity(any()))
                .thenReturn(BluetoothBindingConstants.CONNECTION_SECURITY_ENCRYPTED);

        assertEquals(HCIStatusCode.SUCCESS, device().connectNative());

        verify(nativeDevice()).setConnSecurity(BTSecurityLevel.ENC_ONLY, SMPIOCapability.NO_INPUT_NO_OUTPUT);
        verify(nativeDevice(), never()).setConnSecurityAuto(any());
        verify(nativeDevice()).connectLE(anyShort(), anyShort(), anyShort(), anyShort(), anyShort(), anyShort());
    }

    @Test
    void connectNativeRequestsAuthenticatedSecurityForPinMode() {
        device().updateBTDevice(nativeDevice());
        when(nativeDevice().connectLE(anyShort(), anyShort(), anyShort(), anyShort(), anyShort(), anyShort()))
                .thenReturn(HCIStatusCode.SUCCESS);
        // "pin" -> authenticated Passkey Entry: ENC_AUTH (encrypted + MITM) with KEYBOARD_ONLY (we input the key).
        when(bridge().getDeviceConnectionSecurity(any())).thenReturn(BluetoothBindingConstants.CONNECTION_SECURITY_PIN);

        assertEquals(HCIStatusCode.SUCCESS, device().connectNative());

        verify(nativeDevice()).setConnSecurity(BTSecurityLevel.ENC_AUTH, SMPIOCapability.KEYBOARD_ONLY);
        verify(nativeDevice(), never()).setConnSecurity(BTSecurityLevel.ENC_ONLY, SMPIOCapability.NO_INPUT_NO_OUTPUT);
    }

    // --- pin mode fails closed: authenticated requirement must be enforced, never downgraded -----------------

    @Test
    void securityRequirementUnmetWhenPinModeButLinkNotAuthenticated() {
        device().updateBTDevice(nativeDevice());
        when(bridge().getDeviceConnectionSecurity(any())).thenReturn(BluetoothBindingConstants.CONNECTION_SECURITY_PIN);
        // SMP negotiated down to Just-Works (unauthenticated) because the peer can't do MITM. The achieved
        // PairingMode is the reliable signal (the requested level can read back too high).
        when(nativeDevice().getPairingMode()).thenReturn(PairingMode.JUST_WORKS);

        assertTrue(device().securityRequirementUnmet(),
                "pin mode over a Just-Works (unauthenticated) link must report its requirement unmet");
    }

    @Test
    void securityRequirementMetWhenPinModeAndLinkAuthenticated() {
        device().updateBTDevice(nativeDevice());
        when(bridge().getDeviceConnectionSecurity(any())).thenReturn(BluetoothBindingConstants.CONNECTION_SECURITY_PIN);
        when(nativeDevice().getPairingMode()).thenReturn(PairingMode.PASSKEY_ENTRY_res);

        assertFalse(device().securityRequirementUnmet(),
                "pin over a genuine Passkey-Entry (authenticated) link satisfies the mode");
    }

    @Test
    void securityRequirementNeverUnmetForNonPinModes() {
        device().updateBTDevice(nativeDevice());
        when(nativeDevice().getPairingMode()).thenReturn(PairingMode.JUST_WORKS);
        for (String mode : List.of(BluetoothBindingConstants.CONNECTION_SECURITY_NONE,
                BluetoothBindingConstants.CONNECTION_SECURITY_ENCRYPTED)) {
            when(bridge().getDeviceConnectionSecurity(any())).thenReturn(mode);
            assertFalse(device().securityRequirementUnmet(), mode + " has no authenticated requirement to enforce");
        }
    }

    @Test
    void securityRequirementUnmetFailsClosedOnNativeThrow() {
        device().updateBTDevice(nativeDevice());
        when(bridge().getDeviceConnectionSecurity(any())).thenReturn(BluetoothBindingConstants.CONNECTION_SECURITY_PIN);
        when(nativeDevice().getPairingMode()).thenThrow(new RuntimeException("cannot read achieved pairing mode"));

        assertTrue(device().securityRequirementUnmet(),
                "if the achieved pairing mode can't be confirmed, an authenticated mode must fail closed");
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

    // --- isPairing: classify the SMP negotiation band (the pairing-aware reconciler's freeze signal) ----------

    @Test
    void isPairingIsFalseWithoutHandle() {
        assertFalse(device().isPairing(), "no native handle -> not pairing");
    }

    @Test
    void isPairingIsTrueDuringNegotiationAndFalseAtTerminalStates() {
        device().updateBTDevice(nativeDevice());

        // Every actively-negotiating SMP state must read as pairing (freeze the connect deadline).
        for (SMPPairingState negotiating : List.of(SMPPairingState.REQUESTED_BY_RESPONDER,
                SMPPairingState.FEATURE_EXCHANGE_STARTED, SMPPairingState.FEATURE_EXCHANGE_COMPLETED,
                SMPPairingState.PASSKEY_EXPECTED, SMPPairingState.NUMERIC_COMPARE_EXPECTED,
                SMPPairingState.PASSKEY_NOTIFY, SMPPairingState.OOB_EXPECTED, SMPPairingState.KEY_DISTRIBUTION)) {
            when(nativeDevice().getPairingState()).thenReturn(negotiating);
            assertTrue(device().isPairing(), "must report pairing in state " + negotiating);
        }

        // The three terminal states must read as NOT pairing (deadline resumes; unbonded connects live here).
        for (SMPPairingState terminal : List.of(SMPPairingState.NONE, SMPPairingState.FAILED,
                SMPPairingState.COMPLETED)) {
            when(nativeDevice().getPairingState()).thenReturn(terminal);
            assertFalse(device().isPairing(), "must NOT report pairing in terminal state " + terminal);
        }
    }

    @Test
    void isPairingSwallowsNativeThrow() {
        device().updateBTDevice(nativeDevice());
        when(nativeDevice().getPairingState()).thenThrow(new RuntimeException("native pairing poll blew up"));

        assertFalse(device().isPairing(), "a throwing pairing-state poll degrades to not-pairing, not a crash");
    }

    // --- stale-bond self-heal: hasStalePairing() / clearStalePairing() ---------------------------

    @Test
    void hasStalePairingReflectsPrePairedState() {
        assertFalse(device().hasStalePairing(), "no handle -> no stored keys");

        device().updateBTDevice(nativeDevice());
        when(nativeDevice().isPrePaired()).thenReturn(true);
        assertTrue(device().hasStalePairing(), "pre-paired device holds a stored key a reconnect would reuse");

        when(nativeDevice().isPrePaired()).thenReturn(false);
        assertFalse(device().hasStalePairing());
    }

    @Test
    void clearStalePairingUnpairsTheNativeDevice() {
        device().updateBTDevice(nativeDevice());

        device().clearStalePairing();

        // unpair() drops the stored SMP keys so the next connect re-pairs fresh rather than reusing a dead key.
        verify(nativeDevice()).unpair();
    }

    @Test
    void clearStalePairingWithoutHandleIsANoOp() {
        assertDoesNotThrow(() -> device().clearStalePairing());
    }

    @Test
    void idIsTheAddress() {
        assertEquals(ADDRESS.toString(), device().id());
    }

    // --- write characteristic --------------------------------------------------------------------

    @Test
    void writeCharacteristicUsesAcknowledgedWriteWhenSupported() throws Exception {
        BTGattChar gattChar = connectWithChar(GattCharPropertySet.Type.WriteWithAck);
        when(gattChar.writeValue(any(), anyBoolean())).thenReturn(true);
        byte[] payload = { 0x01, 0x02 };

        device().writeCharacteristic(characteristic(), payload).get();

        // A characteristic that supports write-with-response must be written acknowledged (withResponse=true).
        verify(gattChar).writeValue(payload, true);
    }

    @Test
    void writeCharacteristicUsesUnacknowledgedWriteWhenAckNotSupported() throws Exception {
        BTGattChar gattChar = connectWithChar(GattCharPropertySet.Type.WriteNoAck);
        when(gattChar.writeValue(any(), anyBoolean())).thenReturn(true);

        device().writeCharacteristic(characteristic(), new byte[] { 0x03 }).get();

        verify(gattChar).writeValue(any(), eq(false)); // write-without-response
    }

    @Test
    void writeCharacteristicToDisconnectedDeviceFails() {
        // No handle / not connected: the write must complete exceptionally, not silently succeed.
        CompletableFuture<@Nullable Void> f = device().writeCharacteristic(characteristic(), new byte[] { 0x00 });
        assertThrows(Exception.class, f::get);
    }

    // --- notifications (subscribe) ---------------------------------------------------------------

    @Test
    void enableNotificationsRegistersListenerAndEnablesCccd() throws Exception {
        BTGattChar gattChar = connectWithChar(GattCharPropertySet.Type.Notify);
        when(gattChar.addCharListener(any())).thenReturn(true);
        when(gattChar.enableNotificationOrIndication(any())).thenReturn(true);

        device().enableNotifications(characteristic()).get();

        verify(gattChar).addCharListener(any());
        verify(gattChar).enableNotificationOrIndication(any()); // the CCCD write
        assertTrue(device().isNotifying(characteristic()));
    }

    @Test
    void enableNotificationsIsIdempotent() throws Exception {
        BTGattChar gattChar = connectWithChar(GattCharPropertySet.Type.Notify);
        when(gattChar.addCharListener(any())).thenReturn(true);
        when(gattChar.enableNotificationOrIndication(any())).thenReturn(true);

        device().enableNotifications(characteristic()).get();
        device().enableNotifications(characteristic()).get(); // second call: already enabled

        // The native listener must be registered only once (the reservation is atomic; a second enable is a no-op).
        verify(gattChar, times(1)).addCharListener(any());
    }

    @Test
    void enableNotificationsRollsBackReservationOnCccdFailure() throws Exception {
        BTGattChar gattChar = connectWithChar(GattCharPropertySet.Type.Notify);
        when(gattChar.addCharListener(any())).thenReturn(true);
        when(gattChar.enableNotificationOrIndication(any())).thenReturn(false); // CCCD write fails

        CompletableFuture<@Nullable Void> f = device().enableNotifications(characteristic());
        assertThrows(Exception.class, f::get);

        // On failure the slot must be released so a later retry can re-register (not left orphaned as "notifying").
        assertFalse(device().isNotifying(characteristic()));
    }

    @Test
    void disableNotificationsRemovesTheListener() throws Exception {
        BTGattChar gattChar = connectWithChar(GattCharPropertySet.Type.Notify);
        when(gattChar.addCharListener(any())).thenReturn(true);
        when(gattChar.enableNotificationOrIndication(any())).thenReturn(true);
        device().enableNotifications(characteristic()).get();

        device().disableNotifications(characteristic()).get();

        verify(gattChar).removeCharListener(any());
        assertFalse(device().isNotifying(characteristic()));
    }

    @Test
    void emptyCharacteristicValueDoesNotInvalidateGatt() throws Exception {
        BTGattChar gattChar = connectWithChar(GattCharPropertySet.Type.Read);
        when(gattChar.readValue()).thenReturn(new byte[0]);

        assertArrayEquals(new byte[0], device().readCharacteristic(characteristic()).get());
        assertTrue(device().hasNativeDevice());
        verify(nativeDevice(), never()).disconnect();
    }

    @Test
    void failedNativeReadInvalidatesGatt() {
        BTGattChar gattChar = connectWithChar(GattCharPropertySet.Type.Read);
        when(gattChar.readValue()).thenReturn(null);

        assertThrows(Exception.class, () -> device().readCharacteristic(characteristic()).get());
        assertFalse(device().hasNativeDevice());
        verify(nativeDevice()).disconnect();
        verify(bridge()).requeueReconcile();
    }

    @Test
    void notificationCallbackDoesNotBlockOnOpenhabListeners() throws Exception {
        BTGattChar gattChar = connectWithChar(GattCharPropertySet.Type.Notify);
        when(gattChar.addCharListener(any())).thenReturn(true);
        when(gattChar.enableNotificationOrIndication(any())).thenReturn(true);
        BluetoothDeviceListener listener = mock(BluetoothDeviceListener.class);
        CountDownLatch listenerEntered = new CountDownLatch(1);
        CountDownLatch releaseListener = new CountDownLatch(1);
        CountDownLatch listenerCompleted = new CountDownLatch(1);
        doAnswer(invocation -> {
            listenerEntered.countDown();
            releaseListener.await(2, TimeUnit.SECONDS);
            listenerCompleted.countDown();
            return null;
        }).when(listener).onCharacteristicUpdate(any(), any());
        device().addListener(listener);
        device().enableNotifications(characteristic()).get();

        ArgumentCaptor<BTGattCharListener> listenerCaptor = ArgumentCaptor.forClass(BTGattCharListener.class);
        verify(gattChar).addCharListener(listenerCaptor.capture());

        CompletableFuture<Void> callback = CompletableFuture
                .runAsync(() -> listenerCaptor.getValue().notificationReceived(gattChar, new byte[] { 0x01 }, 1L));
        try {
            callback.get(200, TimeUnit.MILLISECONDS);
            assertTrue(listenerEntered.await(1, TimeUnit.SECONDS), "event delivery should still run asynchronously");
            assertFalse(listenerCompleted.await(100, TimeUnit.MILLISECONDS),
                    "listener should still be blocked while native callback has already returned");
        } finally {
            releaseListener.countDown();
        }
        assertTrue(listenerCompleted.await(1, TimeUnit.SECONDS));
    }

    @Test
    void notificationsAreDeliveredInSubmissionOrder() throws Exception {
        BTGattChar gattChar = connectWithChar(GattCharPropertySet.Type.Notify);
        when(gattChar.addCharListener(any())).thenReturn(true);
        when(gattChar.enableNotificationOrIndication(any())).thenReturn(true);

        int count = 200;
        List<Integer> received = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch all = new CountDownLatch(count);
        BluetoothDeviceListener listener = mock(BluetoothDeviceListener.class);
        doAnswer(invocation -> {
            byte[] value = invocation.getArgument(1);
            int seq = ((value[0] & 0xFF) << 8) | (value[1] & 0xFF);
            if (seq % 2 == 0) {
                Thread.sleep(1); // provoke inversions if delivery were parallel (notify pool has 4 threads)
            }
            received.add(seq);
            all.countDown();
            return null;
        }).when(listener).onCharacteristicUpdate(any(), any());
        device().addListener(listener);
        device().enableNotifications(characteristic()).get();

        ArgumentCaptor<BTGattCharListener> listenerCaptor = ArgumentCaptor.forClass(BTGattCharListener.class);
        verify(gattChar).addCharListener(listenerCaptor.capture());
        for (int i = 0; i < count; i++) {
            listenerCaptor.getValue().notificationReceived(gattChar, new byte[] { (byte) (i >> 8), (byte) i }, i);
        }

        assertTrue(all.await(10, TimeUnit.SECONDS), "all notifications must be delivered");
        for (int i = 0; i < count; i++) {
            assertEquals(i, received.get(i), "delivery order must match native submission order at index " + i);
        }
    }

    @Test
    void repeatedCharacteristicUuidIsScopedByService() throws Exception {
        device().updateBTDevice(nativeDevice());
        when(nativeDevice().getConnected()).thenReturn(true);

        BTGattChar firstNativeChar = mock(BTGattChar.class);
        BTGattChar secondNativeChar = mock(BTGattChar.class);
        BTGattService firstNativeService = mock(BTGattService.class);
        BTGattService secondNativeService = mock(BTGattService.class);
        when(firstNativeService.getUUID()).thenReturn(SERVICE_UUID.toString());
        when(secondNativeService.getUUID()).thenReturn(SECOND_SERVICE_UUID.toString());
        when(firstNativeService.getChars()).thenReturn(List.of(firstNativeChar));
        when(secondNativeService.getChars()).thenReturn(List.of(secondNativeChar));
        for (BTGattChar nativeChar : List.of(firstNativeChar, secondNativeChar)) {
            when(nativeChar.getUUID()).thenReturn(CHAR_UUID.toString());
            when(nativeChar.getProperties()).thenReturn(new GattCharPropertySet(GattCharPropertySet.Type.Notify)
                    .set(GattCharPropertySet.Type.Read).set(GattCharPropertySet.Type.WriteWithAck));
            when(nativeChar.addCharListener(any())).thenReturn(true);
            when(nativeChar.enableNotificationOrIndication(any())).thenReturn(true);
            when(nativeChar.writeValue(any(), eq(true))).thenReturn(true);
        }
        when(firstNativeChar.getService()).thenReturn(firstNativeService);
        when(secondNativeChar.getService()).thenReturn(secondNativeService);
        when(firstNativeChar.readValue()).thenReturn(new byte[] { 0x01 });
        when(secondNativeChar.readValue()).thenReturn(new byte[] { 0x02 });
        when(nativeDevice().getGattServices()).thenReturn(List.of(firstNativeService, secondNativeService));

        assertTrue(device().discoverServices());
        BluetoothService firstService = Objects.requireNonNull(device().getServices(SERVICE_UUID));
        BluetoothService secondService = Objects.requireNonNull(device().getServices(SECOND_SERVICE_UUID));
        BluetoothCharacteristic first = Objects.requireNonNull(firstService.getCharacteristic(CHAR_UUID));
        BluetoothCharacteristic second = Objects.requireNonNull(secondService.getCharacteristic(CHAR_UUID));

        assertArrayEquals(new byte[] { 0x01 }, device().readCharacteristic(first).get());
        assertArrayEquals(new byte[] { 0x02 }, device().readCharacteristic(second).get());
        device().writeCharacteristic(first, new byte[] { 0x11 }).get();
        device().writeCharacteristic(second, new byte[] { 0x22 }).get();
        verify(firstNativeChar).writeValue(new byte[] { 0x11 }, true);
        verify(secondNativeChar).writeValue(new byte[] { 0x22 }, true);

        device().enableNotifications(first).get();
        device().enableNotifications(second).get();
        ArgumentCaptor<BTGattCharListener> firstListener = ArgumentCaptor.forClass(BTGattCharListener.class);
        ArgumentCaptor<BTGattCharListener> secondListener = ArgumentCaptor.forClass(BTGattCharListener.class);
        verify(firstNativeChar).addCharListener(firstListener.capture());
        verify(secondNativeChar).addCharListener(secondListener.capture());

        List<UUID> notifiedServices = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch received = new CountDownLatch(2);
        BluetoothDeviceListener listener = mock(BluetoothDeviceListener.class);
        doAnswer(invocation -> {
            BluetoothCharacteristic characteristic = invocation.getArgument(0);
            notifiedServices.add(Objects.requireNonNull(characteristic.getService()).getUuid());
            received.countDown();
            return null;
        }).when(listener).onCharacteristicUpdate(any(), any());
        device().addListener(listener);

        firstListener.getValue().notificationReceived(firstNativeChar, new byte[] { 0x31 }, 1L);
        secondListener.getValue().notificationReceived(secondNativeChar, new byte[] { 0x32 }, 2L);
        assertTrue(received.await(2, TimeUnit.SECONDS));
        assertEquals(List.of(SERVICE_UUID, SECOND_SERVICE_UUID), notifiedServices);
    }

    @Test
    void notificationCallbackCopiesPayloadBeforeAsyncFanout() throws Exception {
        ExecutorService operationPool = Executors.newSingleThreadExecutor();
        ExecutorService notifyPool = Executors.newSingleThreadExecutor();
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseNotifyPool = new CountDownLatch(1);
        notifyPool.execute(() -> {
            blockerStarted.countDown();
            try {
                releaseNotifyPool.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        DirectBTBridgeHandler customBridge = mock(DirectBTBridgeHandler.class);
        when(customBridge.getExecutor()).thenReturn(operationPool);
        when(customBridge.getNotifyExecutor()).thenReturn(notifyPool);
        when(customBridge.getResetBudget()).thenReturn(new ResetBudget(8000));
        when(customBridge.isDeviceEnabled(any())).thenReturn(true);
        DirectBTBluetoothDevice dev = new DirectBTBluetoothDevice(customBridge, ADDRESS);
        try {
            dev.updateBTDevice(nativeDevice());
            when(nativeDevice().getConnected()).thenReturn(true);

            BTGattChar gattChar = mock(BTGattChar.class);
            when(gattChar.getUUID()).thenReturn(CHAR_UUID.toString());
            when(gattChar.getProperties()).thenReturn(new GattCharPropertySet(GattCharPropertySet.Type.Notify));
            BTGattService service = mock(BTGattService.class);
            when(service.getUUID()).thenReturn(SERVICE_UUID.toString());
            when(service.getChars()).thenReturn(List.of(gattChar));
            when(gattChar.getService()).thenReturn(service);
            when(nativeDevice().getGattServices()).thenReturn(List.of(service));
            dev.discoverServices();
            when(gattChar.addCharListener(any())).thenReturn(true);
            when(gattChar.enableNotificationOrIndication(any())).thenReturn(true);

            AtomicInteger received = new AtomicInteger(-1);
            CountDownLatch receivedLatch = new CountDownLatch(1);
            BluetoothDeviceListener listener = mock(BluetoothDeviceListener.class);
            doAnswer(invocation -> {
                byte[] value = invocation.getArgument(1);
                received.set(value[0] & 0xFF);
                receivedLatch.countDown();
                return null;
            }).when(listener).onCharacteristicUpdate(any(), any());
            dev.addListener(listener);
            dev.enableNotifications(characteristic()).get();

            ArgumentCaptor<BTGattCharListener> listenerCaptor = ArgumentCaptor.forClass(BTGattCharListener.class);
            verify(gattChar).addCharListener(listenerCaptor.capture());
            assertTrue(blockerStarted.await(1, TimeUnit.SECONDS));

            byte[] nativeBuffer = new byte[] { 0x01 };
            listenerCaptor.getValue().notificationReceived(gattChar, nativeBuffer, 1L);
            nativeBuffer[0] = 0x02;

            releaseNotifyPool.countDown();
            assertTrue(receivedLatch.await(1, TimeUnit.SECONDS));
            assertEquals(1, received.get(), "fanout must see the callback-time payload, not later buffer mutation");
        } finally {
            releaseNotifyPool.countDown();
            operationPool.shutdownNow();
            notifyPool.shutdownNow();
        }
    }

    @Test
    void stuckGattDiscoveryGuardIsStolenAfterCap() throws Exception {
        MutableClock clock = new MutableClock(1_784_200_000_000L);
        DirectBTBluetoothDevice device = new DirectBTBluetoothDevice(bridge(), ADDRESS, clock);
        device.updateBTDevice(nativeDevice());
        when(nativeDevice().getConnected()).thenReturn(true);

        CountDownLatch hungEntered = new CountDownLatch(1);
        CountDownLatch releaseHung = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        when(nativeDevice().getGattServices()).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                hungEntered.countDown();
                releaseHung.await(10, TimeUnit.SECONDS); // simulate a discovery hung in native code
            }
            return List.of();
        });

        Thread hung = new Thread(device::discoverServices, "hung-discovery");
        hung.start();
        try {
            assertTrue(hungEntered.await(2, TimeUnit.SECONDS));

            clock.advance(60_000); // under the cap: the guard must hold
            assertFalse(device.discoverServices(), "under the cap a concurrent discovery must be refused");
            assertEquals(1, calls.get(), "guard held: no second native discovery");

            clock.advance(300_000); // past the cap: the guard is stale, steal it
            device.discoverServices();
            assertEquals(2, calls.get(), "stale guard must be stolen so the device can recover");
        } finally {
            releaseHung.countDown();
            hung.join(2000);
        }
    }

    // --- helpers (unwrap the @Nullable mocks/SUT into @NonNull locals) ----------------------------

    /**
     * Bring the device to "connected with one resolved characteristic" so read/write/notify can run: install the
     * native handle, report connected, and drive discoverServices() over one mock service+characteristic with the
     * given property. Returns the mock BTGattChar for stubbing/verification.
     */
    private BTGattChar connectWithChar(GattCharPropertySet.Type property) {
        device().updateBTDevice(nativeDevice());
        when(nativeDevice().getConnected()).thenReturn(true);

        BTGattChar gattChar = mock(BTGattChar.class);
        when(gattChar.getUUID()).thenReturn(CHAR_UUID.toString());
        when(gattChar.getProperties()).thenReturn(new GattCharPropertySet(property));
        BTGattService service = mock(BTGattService.class);
        when(service.getUUID()).thenReturn(SERVICE_UUID.toString());
        when(service.getChars()).thenReturn(List.of(gattChar));
        when(gattChar.getService()).thenReturn(service);
        when(nativeDevice().getGattServices()).thenReturn(List.of(service));

        device().discoverServices();
        return gattChar;
    }

    private BluetoothCharacteristic characteristic() {
        BluetoothService service = new BluetoothService(SERVICE_UUID, true);
        BluetoothCharacteristic characteristic = new BluetoothCharacteristic(CHAR_UUID, 0);
        service.addCharacteristic(characteristic);
        return characteristic;
    }

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
