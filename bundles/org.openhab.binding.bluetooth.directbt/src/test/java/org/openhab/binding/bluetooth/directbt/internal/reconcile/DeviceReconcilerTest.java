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

import static org.junit.jupiter.api.Assertions.*;
import static org.openhab.binding.bluetooth.directbt.internal.reconcile.ReconcileTestSupport.*;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.direct_bt.HCIStatusCode;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;

/**
 * Regression harness for the {@link DeviceReconciler} — the per-device connection state machine.
 * <p>
 * Each test reproduces a specific field failure we had to fix, so a revert of the fix re-breaks the test.
 * Cross-references: {@code docs/directbt-reconciler-design.md} (canonical model + phase table),
 * {@code docs/directbt-stability-fix-inventory.md} (the change inventory),
 * {@code docs/directbt-csr-wedge-investigation.md} (the CSR quirks). Timing is driven by a {@link MutableClock}
 * (no sleeps); the native device is modelled by {@link FakeDevicePort}.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
class DeviceReconcilerTest {

    // Deadlines mirrored from DeviceReconciler (private there); kept in sync deliberately so a change to the
    // production constant that these tests rely on forces a conscious update here.
    private static final long CONNECT_DEADLINE_MS = 8000;
    private static final long PENDING_RESET_AFTER_MS = 16000;
    private static final long CONNECT_RETRY_MS = 2000;

    // ---------------------------------------------------------------------------------------------
    // Cold-start deadlock (design doc §"Cold start"): a never-discovered wanted device MUST want the
    // scan, else it can never be discovered (no handle -> no scan -> never discovered -> no handle).
    // ---------------------------------------------------------------------------------------------
    @Test
    void coldStartWantedDeviceWithNoHandleWantsDiscovery() {
        FakeDevicePort port = new FakeDevicePort();
        port.wanted = true;
        port.hasNative = false;

        assertTrue(reconciler(port, scanOff(), new MutableClock(START)).wantsDiscovery(),
                "wanted device without a native handle must drive the scan that discovers it");
    }

    @Test
    void deviceWithHandleDoesNotWantDiscovery() {
        FakeDevicePort port = new FakeDevicePort();
        port.wanted = true;
        port.hasNative = true;

        assertFalse(reconciler(port, scanOff(), new MutableClock(START)).wantsDiscovery(),
                "once a handle exists the device wants the scan OFF (connectLE is rejected while scanning)");
    }

    @Test
    void unwantedDeviceNeverWantsDiscovery() {
        FakeDevicePort port = new FakeDevicePort();
        port.wanted = false;
        port.hasNative = false;

        assertFalse(reconciler(port, scanOff(), new MutableClock(START)).wantsDiscovery());
    }

    // needsConnection(): true while a wanted device has a handle but isn't natively connected — the "establishing"
    // signal that makes background/inbox discovery yield so the create-connection isn't starved.
    @Test
    void needsConnectionWhileWantedDeviceWithHandleIsNotYetConnected() {
        FakeDevicePort port = new FakeDevicePort();
        port.wanted = true;
        port.hasNative = true;
        port.nativeConnected = false;

        assertTrue(reconciler(port, scanOff(), new MutableClock(START)).needsConnection());
    }

    @Test
    void doesNotNeedConnectionWhenConnectedOrHandleless() {
        FakeDevicePort connected = new FakeDevicePort();
        connected.wanted = true;
        connected.hasNative = true;
        connected.nativeConnected = true;
        assertFalse(reconciler(connected, scanOff(), new MutableClock(START)).needsConnection(),
                "an already-connected device is not still establishing");

        FakeDevicePort handleless = new FakeDevicePort();
        handleless.wanted = true;
        handleless.hasNative = false;
        assertFalse(reconciler(handleless, scanOff(), new MutableClock(START)).needsConnection(),
                "a device with no handle needs DISCOVERY, not connection-establishment");
    }

    // ---------------------------------------------------------------------------------------------
    // Silent ACL drop (THE defining failure): native link gone but no deviceDisconnected event, so our
    // flag still says CONNECTED. The reconciler must observe native truth and mark disconnected, which is
    // what makes the core reconnect loop resume. It must also clear the stale handle (re-find, don't reuse).
    // ---------------------------------------------------------------------------------------------
    @Test
    void silentDropWithNoEventIsDetectedAndMarksDisconnected() {
        FakeDevicePort port = new FakeDevicePort();
        port.wanted = true;
        port.hasNative = true;
        port.nativeConnected = true;
        port.flagConnected = true;
        port.gattResolved = true;

        DeviceReconciler r = reconciler(port, scanOff(), new MutableClock(START));
        r.reconcile(); // steady state, in sync
        assertEquals(0, port.markDisconnectedCalls);

        port.silentDrop(); // native gone, flag still CONNECTED, NO event fired
        r.reconcile();

        assertEquals(1, port.markDisconnectedCalls, "must notice native!=flag by polling and mark disconnected");
        assertFalse(port.hasNative, "stale handle must be cleared so the device is re-found from a fresh advert");
    }

    // ---------------------------------------------------------------------------------------------
    // GATT-unresolved-after-connect: connected but services not mapped -> resolve, WITHOUT disconnecting.
    // (This is the bug the old T2 workaround mis-handled by disconnecting.)
    // ---------------------------------------------------------------------------------------------
    @Test
    void connectedButGattUnresolvedResolvesWithoutDisconnecting() {
        FakeDevicePort port = new FakeDevicePort();
        port.wanted = true;
        port.hasNative = true;
        port.nativeConnected = true;
        port.flagConnected = true;
        port.gattResolved = false;

        DeviceReconciler r = reconciler(port, scanOff(), new MutableClock(START));
        r.reconcile();

        assertEquals(1, port.resolveGattCalls);
        assertEquals(0, port.disconnectNativeCalls);
        assertEquals(0, port.markDisconnectedCalls);
    }

    // ---------------------------------------------------------------------------------------------
    // Connect blocked while scanning: the controller rejects create-connection while a scan runs, so
    // connectNative() must NOT be issued until scan is observed OFF.
    // ---------------------------------------------------------------------------------------------
    @Test
    void doesNotConnectWhileScanIsOn() {
        FakeDevicePort port = new FakeDevicePort();
        port.wanted = true;
        port.hasNative = true;
        port.nativeConnected = false;

        DeviceReconciler r = reconciler(port, scanOn(), new MutableClock(START));
        r.reconcile();

        assertEquals(0, port.connectNativeCalls, "must wait for scan OFF before create-connection");
    }

    @Test
    void connectsWhenScanIsOffAndHandlePresent() {
        FakeDevicePort port = new FakeDevicePort();
        port.wanted = true;
        port.hasNative = true;
        port.nativeConnected = false;

        DeviceReconciler r = reconciler(port, scanOff(), new MutableClock(START));
        r.reconcile();

        assertEquals(1, port.connectNativeCalls);
        assertEquals(1, port.markConnectingCalls, "issuing connectLE moves us to CONNECTING");
    }

    @Test
    void doesNotConnectBeforeHandleExists() {
        FakeDevicePort port = new FakeDevicePort();
        port.wanted = true;
        port.hasNative = false; // cold start: discovery must surface a handle first

        DeviceReconciler r = reconciler(port, scanOff(), new MutableClock(START));
        r.reconcile();

        assertEquals(0, port.connectNativeCalls, "no connectLE without a native handle");
    }

    // ---------------------------------------------------------------------------------------------
    // Connect retry spacing: must not hammer connectNative() faster than CONNECT_RETRY_MS.
    // ---------------------------------------------------------------------------------------------
    @Test
    void connectAttemptsAreSpacedByRetryInterval() {
        FakeDevicePort port = new FakeDevicePort();
        port.wanted = true;
        port.hasNative = true;
        port.connectResult = HCIStatusCode.SUCCESS;

        MutableClock clock = new MutableClock(START);
        DeviceReconciler r = reconciler(port, scanOff(), clock);

        r.reconcile(); // attempt #1 -> CONNECTING
        assertEquals(1, port.connectNativeCalls);
        port.flagConnecting = true;

        // Sit in CONNECTING until past the connect deadline, then tick to clear the stuck pending. Advance well
        // past both the deadline and the base-class backoff window so the clearing tick is not itself backed off.
        clock.advance(CONNECT_DEADLINE_MS + 10_000);
        r.reconcile(); // clears pending -> markDisconnected -> back to a connectable state
        int callsAfterClear = port.connectNativeCalls;
        // markDisconnected() cleared the handle; the device is re-found, restore it for the reconnect attempt.
        port.hasNative = true;

        r.reconcile(); // immediately again: within CONNECT_RETRY_MS of the last attempt -> must NOT reconnect yet
        assertEquals(callsAfterClear, port.connectNativeCalls, "connectLE must be spaced by the retry interval");

        clock.advance(CONNECT_RETRY_MS + 10_000); // past retry spacing AND backoff
        r.reconcile();
        assertTrue(port.connectNativeCalls > callsAfterClear, "after the retry interval a new attempt may fire");
    }

    // ---------------------------------------------------------------------------------------------
    // Pending-stuck (CONNECTING that never connects): at CONNECT_DEADLINE_MS clear the stuck pending; at
    // PENDING_RESET_AFTER_MS escalate to ONE budgeted adapter reset.
    // ---------------------------------------------------------------------------------------------
    @Test
    void stuckConnectingClearsPendingAtDeadline() {
        FakeDevicePort port = new FakeDevicePort();
        port.wanted = true;
        port.hasNative = true;
        port.connectResult = HCIStatusCode.SUCCESS;

        MutableClock clock = new MutableClock(START);
        DeviceReconciler r = reconciler(port, scanOff(), clock);

        r.reconcile(); // -> CONNECTING (but connectNative never "settles" to nativeConnected)
        assertEquals(1, port.connectNativeCalls);
        port.flagConnecting = true; // reflect that we are CONNECTING

        clock.advance(CONNECT_DEADLINE_MS - 100);
        r.reconcile();
        assertEquals(0, port.disconnectNativeCalls, "before the deadline the pending attempt is given time");

        // Advance clearly past the connect deadline AND past the base-class backoff window (which grew after the
        // previous ticks), so the clearing tick actually runs act() rather than being backed off.
        clock.advance(10_000);
        r.reconcile();
        assertEquals(1, port.disconnectNativeCalls, "past the connect deadline the stuck pending is cleared");
    }

    @Test
    void stuckConnectingEscalatesToOneAdapterResetPastHardDeadline() {
        FakeDevicePort port = new FakeDevicePort();
        port.wanted = true;
        port.hasNative = true;
        port.connectResult = HCIStatusCode.SUCCESS;

        MutableClock clock = new MutableClock(START);
        AtomicInteger resets = new AtomicInteger();
        DeviceReconciler r = new DeviceReconciler(logger(), port, scanOff(), budget(clock), resets::incrementAndGet,
                clock);

        r.reconcile(); // -> CONNECTING
        port.flagConnecting = true;

        clock.advance(PENDING_RESET_AFTER_MS + 1);
        r.reconcile();

        assertEquals(1, resets.get(), "a create-connection wedged past the hard deadline requests an adapter reset");
    }

    // ---------------------------------------------------------------------------------------------
    // COMMAND_DISALLOWED spiral (CSR quirk): a connect rejected outright must reset to DISCONNECTED (no
    // phantom CONNECTING) AND request exactly one budgeted adapter reset.
    // ---------------------------------------------------------------------------------------------
    @Test
    void connectCommandDisallowedResetsFlagAndRequestsAdapterReset() {
        FakeDevicePort port = new FakeDevicePort();
        port.wanted = true;
        port.hasNative = true;
        port.connectResult = HCIStatusCode.COMMAND_DISALLOWED;

        MutableClock clock = new MutableClock(START);
        AtomicInteger resets = new AtomicInteger();
        DeviceReconciler r = new DeviceReconciler(logger(), port, scanOff(), budget(clock), resets::incrementAndGet,
                clock);

        r.reconcile();

        assertEquals(1, port.connectNativeCalls);
        assertFalse(port.flagConnecting, "a rejected connect must not leave us stuck in a phantom CONNECTING");
        assertEquals(1, port.markDisconnectedCalls, "reset to DISCONNECTED so the next tick re-evaluates cleanly");
        assertEquals(1, resets.get(), "COMMAND_DISALLOWED triggers one budgeted adapter reset");
    }

    // ---------------------------------------------------------------------------------------------
    // Unwanted / alwaysConnected=false: an unwanted device that is connected must be disconnected + flag
    // cleared (do not hold the ACL open and fight the core's idle-disconnect).
    // ---------------------------------------------------------------------------------------------
    @Test
    void unwantedConnectedDeviceIsDisconnected() {
        FakeDevicePort port = new FakeDevicePort();
        port.wanted = false;
        port.hasNative = true;
        port.nativeConnected = true;
        port.flagConnected = true;
        port.gattResolved = true;

        DeviceReconciler r = reconciler(port, scanOff(), new MutableClock(START));
        r.reconcile();

        assertEquals(1, port.disconnectNativeCalls);
        assertEquals(1, port.markDisconnectedCalls);
        assertEquals(0, port.resolveGattCalls);
    }

    @Test
    void unwantedConnectingDeviceIsMarkedDisconnected() {
        FakeDevicePort port = new FakeDevicePort();
        port.wanted = false;
        port.hasNative = true;
        port.flagConnecting = true;

        DeviceReconciler r = reconciler(port, scanOff(), new MutableClock(START));
        r.reconcile();

        assertEquals(1, port.markDisconnectedCalls);
        assertEquals(0, port.disconnectNativeCalls);
        assertEquals(0, port.resolveGattCalls);
    }

    // ---------------------------------------------------------------------------------------------
    // Happy path: native connection settles after an accepted connect; flag syncs up, then GATT resolves.
    // ---------------------------------------------------------------------------------------------
    @Test
    void fullConnectLifecycleReachesInSync() {
        FakeDevicePort port = new FakeDevicePort();
        port.wanted = true;
        port.hasNative = true;

        MutableClock clock = new MutableClock(START);
        DeviceReconciler r = reconciler(port, scanOff(), clock);

        r.reconcile(); // issue connectLE -> CONNECTING
        assertEquals(1, port.connectNativeCalls);

        port.settleNativeConnected(); // controller completes the connection (deviceConnected)
        clock.advance(10_000); // past the base-class backoff so the next corrective tick runs
        r.reconcile(); // observe native connected -> mark connected
        assertEquals(1, port.markConnectedCalls);
        assertTrue(port.flagConnected);

        clock.advance(10_000);
        r.reconcile(); // connected but GATT unresolved -> resolve
        assertEquals(1, port.resolveGattCalls);
        assertTrue(port.gattResolved);

        assertTrue(r.reconcile(), "connected + flag + GATT resolved == in sync");
    }

    @Test
    void lastObservedExposesThePolledSnapshot() {
        FakeDevicePort port = new FakeDevicePort();
        port.wanted = true;
        port.hasNative = true;
        port.nativeConnected = true;
        port.flagConnected = true;
        port.gattResolved = true;

        DeviceReconciler r = reconciler(port, scanOff(), new MutableClock(START));
        assertNull(r.lastObserved(), "no snapshot before the first tick");

        r.reconcile();
        DeviceReconciler.Observed o = r.lastObserved();
        assertNotNull(o);
        assertTrue(o.nativeConnected);
        assertTrue(o.gattResolved);
    }

    // --- helpers ---------------------------------------------------------------------------------

    private static DeviceReconciler reconciler(FakeDevicePort port, BooleanSupplier scanIsOff, MutableClock clock) {
        return new DeviceReconciler(logger(), port, scanIsOff, budget(clock), () -> {
        }, clock);
    }

    private static BooleanSupplier scanOff() {
        return () -> true;
    }

    private static BooleanSupplier scanOn() {
        AtomicBoolean off = new AtomicBoolean(false);
        return off::get;
    }
}
