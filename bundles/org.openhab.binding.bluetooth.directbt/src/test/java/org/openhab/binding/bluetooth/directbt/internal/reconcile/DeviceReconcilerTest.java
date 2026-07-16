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
 * Timing is driven by a {@link MutableClock} (no sleeps); the native device is modelled by
 * {@link FakeDevicePort}.
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

    @Test
    void connectedButGattAlreadyResolvingWaitsWithoutCountingFailure() {
        FakeDevicePort port = new FakeDevicePort();
        port.wanted = true;
        port.hasNative = true;
        port.nativeConnected = true;
        port.flagConnected = true;
        port.gattResolved = false;
        port.gattResolving = true;

        MutableClock clock = new MutableClock(START);
        DeviceReconciler r = reconciler(port, scanOff(), clock);

        for (int i = 0; i < 4; i++) {
            r.reconcile();
            clock.advance(10_000);
        }

        assertEquals(0, port.resolveGattCalls, "must not start another discovery while one is in flight");
        assertEquals(0, port.disconnectNativeCalls, "in-flight GATT discovery is progress, not a stale link");
        assertEquals(0, port.markDisconnectedCalls, "must not tear down a link while its service walk is running");
    }

    @Test
    void connectedButGattResolvingForeverEscalatesAfterCap() {
        FakeDevicePort port = new FakeDevicePort();
        port.wanted = true;
        port.hasNative = true;
        port.nativeConnected = true;
        port.flagConnected = true;
        port.gattResolved = false;
        port.gattResolving = true; // never clears: the discovery thread hung in native code

        MutableClock clock = new MutableClock(START);
        DeviceReconciler r = reconciler(port, scanOff(), clock);

        r.reconcile(); // first observation starts the in-flight age
        clock.advance(119_000);
        r.reconcile(); // still under the 120 s cap
        assertEquals(0, port.markDisconnectedCalls, "under the cap the reconciler must keep waiting");

        clock.advance(2_000);
        r.reconcile(); // past the cap
        assertEquals(1, port.markDisconnectedCalls, "a hung discovery must not suppress recovery forever");
        assertEquals(0, port.resolveGattCalls, "the hung discovery must not be joined by another one");
    }

    // ---------------------------------------------------------------------------------------------
    // Event-driven fast retry: a deviceDisconnected event during CONNECTING clears the pending attempt
    // immediately instead of waiting out the connect deadline; pairing-ladder churn events are discarded.
    // ---------------------------------------------------------------------------------------------
    @Test
    void connectAttemptFailureEventClearsPendingBeforeDeadline() {
        FakeDevicePort port = new FakeDevicePort();
        port.wanted = true;
        port.hasNative = true;
        port.connectResult = HCIStatusCode.SUCCESS;

        MutableClock clock = new MutableClock(START);
        DeviceReconciler r = reconciler(port, scanOff(), clock);

        r.reconcile(); // attempt #1 -> CONNECTING
        assertEquals(1, port.connectNativeCalls);
        port.flagConnecting = true;

        port.connectAttemptFailed = true; // 0x3e-style deviceDisconnected arrived shortly after the attempt
        clock.advance(5_000); // past the tick backoff but well under CONNECT_DEADLINE_MS
        r.reconcile();
        assertEquals(1, port.markDisconnectedCalls, "a failed attempt must be cleared as soon as its event arrives");
        assertEquals(1, port.disconnectNativeCalls, "the failed pending attempt gets a best-effort native disconnect");
    }

    // ---------------------------------------------------------------------------------------------
    // GATT warm-up grace: with event-expedited ticks, the first resolve runs ~300 ms after the
    // connection event — before native GATT is servable. Instant empty resolves inside the grace must
    // not burn the silent-drop streak; after the grace they count again.
    // ---------------------------------------------------------------------------------------------
    @Test
    void instantEmptyResolvesRightAfterConnectDoNotBurnTheStreak() {
        FakeDevicePort port = new FakeDevicePort();
        port.wanted = true;
        port.hasNative = true;
        port.nativeConnected = true;
        port.flagConnected = false; // first tick marks connected, stamping the warm-up clock
        port.gattResolved = false;
        port.resolveSucceeds = false; // native GATT never becomes servable in this scenario

        MutableClock clock = new MutableClock(START);
        DeviceReconciler r = reconciler(port, scanOff(), clock);

        for (int i = 0; i < 5; i++) { // 0 .. 3.6 s after connect: all inside the warm-up grace
            r.expediteNextAct(); // events would do this in production
            r.reconcile();
            clock.advance(900);
        }
        assertEquals(5, port.resolveGattCalls, "warm-up resolves keep retrying");
        assertEquals(0, port.markDisconnectedCalls, "instant empty resolves during warm-up are not failures");

        clock.advance(2_000); // past the 5 s grace: unresolved-and-instant now counts as evidence again
        for (int i = 0; i < 3; i++) {
            r.expediteNextAct();
            r.reconcile();
            clock.advance(900);
        }
        assertEquals(1, port.markDisconnectedCalls, "past the grace the silent-drop streak must still fire");
    }

    @Test
    void pairingChurnDiscardsFailureEventsInsteadOfFastFailing() {
        FakeDevicePort port = new FakeDevicePort();
        port.wanted = true;
        port.hasNative = true;
        port.connectResult = HCIStatusCode.SUCCESS;

        MutableClock clock = new MutableClock(START);
        DeviceReconciler r = reconciler(port, scanOff(), clock);

        r.reconcile(); // -> CONNECTING
        port.flagConnecting = true;
        port.pairing = true;
        port.connectAttemptFailed = true; // SMP ladder connect/disconnect churn

        clock.advance(5_000);
        r.reconcile(); // freeze: discard the event, make no progress judgement
        assertEquals(0, port.markDisconnectedCalls, "pairing churn must not tear the negotiation down");
        assertFalse(port.connectAttemptFailed, "the churn event must be consumed by the freeze");

        port.pairing = false;
        clock.advance(1_000);
        r.reconcile(); // resume: the frozen deadline continues, no stale event may fast-fail the attempt
        assertEquals(0, port.markDisconnectedCalls, "no stale churn event may fast-fail the resumed attempt");
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
    // COMMAND_DISALLOWED: a connect rejected outright must reset to DISCONNECTED (no phantom CONNECTING).
    // One or two rejections are the benign connect/scan race and must NOT trigger the adapter reset (a
    // native call observed to hang); only a persistent streak is the wedged-controller (CSR quirk) case.
    // ---------------------------------------------------------------------------------------------
    @Test
    void connectCommandDisallowedResetsAdapterOnlyAfterAStreak() {
        FakeDevicePort port = new FakeDevicePort();
        port.wanted = true;
        port.hasNative = true;
        port.connectResult = HCIStatusCode.COMMAND_DISALLOWED;

        MutableClock clock = new MutableClock(START);
        AtomicInteger resets = new AtomicInteger();
        DeviceReconciler r = new DeviceReconciler(logger(), port, scanOff(), budget(clock), resets::incrementAndGet,
                clock);

        r.reconcile(); // rejection #1
        assertEquals(1, port.connectNativeCalls);
        assertFalse(port.flagConnecting, "a rejected connect must not leave us stuck in a phantom CONNECTING");
        assertEquals(1, port.markDisconnectedCalls, "reset to DISCONNECTED so the next tick re-evaluates cleanly");
        assertEquals(0, resets.get(), "a single COMMAND_DISALLOWED is the scan race, not a wedged controller");

        for (int i = 0; i < 2; i++) { // rejections #2 and #3
            port.hasNative = true; // markDisconnected cleared the handle; the device is re-found each time
            clock.advance(15_000); // past connect retry spacing and act backoff
            r.expediteNextAct();
            r.reconcile();
        }
        assertEquals(3, port.connectNativeCalls);
        assertEquals(1, resets.get(), "a persistent COMMAND_DISALLOWED streak requests one budgeted adapter reset");
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

    // ---------------------------------------------------------------------------------------------
    // Pairing-aware connect deadline (encryption regression): setConnSecurityAuto does several
    // connect/disconnect cycles to negotiate SMP keys, leaving no stable native link for seconds. The
    // reconciler must FREEZE its connect deadline while pairing is in progress, else it tears the negotiation
    // down mid-flight (the endless connect/clear-pending flap we saw when setConnSecurityAuto was first tried).
    // ---------------------------------------------------------------------------------------------
    @Test
    void pairingInProgressFreezesTheConnectDeadline() {
        FakeDevicePort port = new FakeDevicePort();
        port.wanted = true;
        port.hasNative = true;
        port.connectResult = HCIStatusCode.SUCCESS;

        MutableClock clock = new MutableClock(START);
        DeviceReconciler r = reconciler(port, scanOff(), clock);

        r.reconcile(); // -> CONNECTING
        port.flagConnecting = true;
        port.pairing = true; // SMP negotiation begins

        // Sit well past BOTH the connect deadline and the hard reset deadline while pairing stays in progress.
        clock.advance(PENDING_RESET_AFTER_MS + 60_000);
        r.reconcile();

        assertEquals(0, port.disconnectNativeCalls,
                "the connect deadline must be frozen while SMP is negotiating (do not tear pairing down)");
        assertEquals(0, port.markDisconnectedCalls, "pairing in progress must not be dropped to DISCONNECTED");
    }

    @Test
    void pairingThenSuccessfulConnectReachesInSyncWithoutTeardown() {
        FakeDevicePort port = new FakeDevicePort();
        port.wanted = true;
        port.hasNative = true;

        MutableClock clock = new MutableClock(START);
        DeviceReconciler r = reconciler(port, scanOff(), clock);

        r.reconcile(); // issue connectLE -> CONNECTING
        port.flagConnecting = true;
        port.pairing = true;

        // A long negotiation (past the deadline) — frozen, so no teardown.
        clock.advance(CONNECT_DEADLINE_MS + 20_000);
        r.reconcile();
        assertEquals(0, port.disconnectNativeCalls, "no teardown during negotiation");

        // SMP completes and the link comes up.
        port.pairing = false;
        port.settleNativeConnected();
        clock.advance(10_000); // past base-class backoff
        r.reconcile(); // observe native connected -> mark connected
        assertEquals(1, port.markConnectedCalls);
        assertEquals(0, port.disconnectNativeCalls, "a completed pairing must never have been torn down");

        clock.advance(10_000);
        r.reconcile(); // GATT resolve
        assertTrue(r.reconcile(), "connected + flag + GATT resolved after pairing == in sync");
    }

    @Test
    void deadlineResumesAfterPairingEndsWithoutSettling() {
        // If pairing ends (e.g. FAILED/NONE) but the link still does not come up, the FROZEN deadline must
        // RESUME — the time spent pairing does not count, but the pre-pairing connecting time does — so a device
        // that never settles is eventually cleared and retried rather than hanging in CONNECTING forever.
        FakeDevicePort port = new FakeDevicePort();
        port.wanted = true;
        port.hasNative = true;
        port.connectResult = HCIStatusCode.SUCCESS;

        MutableClock clock = new MutableClock(START);
        DeviceReconciler r = reconciler(port, scanOff(), clock);

        r.reconcile(); // -> CONNECTING
        port.flagConnecting = true;

        // Accrue almost the whole deadline BEFORE pairing starts.
        clock.advance(CONNECT_DEADLINE_MS - 1000);
        port.pairing = true;
        r.reconcile(); // enters the freeze; no teardown
        assertEquals(0, port.disconnectNativeCalls);

        // Long negotiation, then it ends WITHOUT the link coming up.
        clock.advance(30_000);
        port.pairing = false;
        r.reconcile(); // pairing ended: deadline resumes. Pre-pairing elapsed (deadline-1000) is < deadline -> no clear
                       // yet
        assertEquals(0, port.disconnectNativeCalls, "just under the (resumed) deadline: still given time");

        // A little more real time past the resumed deadline (and past backoff) -> now it clears.
        clock.advance(10_000);
        r.reconcile();
        assertEquals(1, port.disconnectNativeCalls,
                "once pairing ends and the resumed deadline passes, the stuck pending is cleared");
    }

    // ---------------------------------------------------------------------------------------------
    // Stale-bond self-heal (encryption regression, found live 2026-07-02): a pre-paired reconnect that never
    // establishes is reusing a stored SMP key the peer no longer honours (peripheral forgot the bond). When the
    // connect deadline fires while the device is pre-paired, the reconciler must CLEAR the stale bond so the next
    // attempt re-pairs fresh — otherwise it loops forever (connectNative->SUCCESS, never a native link).
    // ---------------------------------------------------------------------------------------------
    @Test
    void stuckConnectWhilePrePairedClearsTheStaleBond() {
        FakeDevicePort port = new FakeDevicePort();
        port.wanted = true;
        port.hasNative = true;
        port.prePaired = true; // holds a stored key a reconnect reuses
        port.connectResult = HCIStatusCode.SUCCESS;

        MutableClock clock = new MutableClock(START);
        DeviceReconciler r = reconciler(port, scanOff(), clock);

        r.reconcile(); // -> CONNECTING (never settles to nativeConnected: the stale-key encrypt fails silently)
        port.flagConnecting = true;

        clock.advance(CONNECT_DEADLINE_MS + 10_000);
        r.reconcile(); // deadline fires while pre-paired -> clear the stale bond

        assertEquals(1, port.clearStalePairingCalls, "a stuck pre-paired reconnect must clear the stale bond");
        assertFalse(port.prePaired, "after clearing, the next attempt re-pairs fresh (not reusing the dead key)");
    }

    @Test
    void stuckConnectWhenNotPrePairedDoesNotUnpair() {
        FakeDevicePort port = new FakeDevicePort();
        port.wanted = true;
        port.hasNative = true;
        port.prePaired = false; // unbonded (security=none) — nothing to clear
        port.connectResult = HCIStatusCode.SUCCESS;

        MutableClock clock = new MutableClock(START);
        DeviceReconciler r = reconciler(port, scanOff(), clock);

        r.reconcile();
        port.flagConnecting = true;

        clock.advance(CONNECT_DEADLINE_MS + 10_000);
        r.reconcile();

        assertEquals(0, port.clearStalePairingCalls, "an unbonded device has no stale bond to clear");
    }

    // ---------------------------------------------------------------------------------------------
    // Security enforcement (fail closed): when the configured mode requires authentication ("pin") but the link
    // negotiated down (peer can't do MITM), the reconciler must REFUSE — disconnect and NOT resolve GATT — rather
    // than silently expose data over a weaker-than-demanded link.
    // ---------------------------------------------------------------------------------------------
    @Test
    void connectedButSecurityRequirementUnmetRefusesAndDoesNotResolveGatt() {
        FakeDevicePort port = new FakeDevicePort();
        port.wanted = true;
        port.hasNative = true;
        port.nativeConnected = true;
        port.flagConnected = true;
        port.gattResolved = false;
        port.securityUnmet = true; // authenticated mode requested but achieved link is unauthenticated

        DeviceReconciler r = reconciler(port, scanOff(), new MutableClock(START));
        r.reconcile();

        assertEquals(0, port.resolveGattCalls, "must NOT resolve/expose GATT when the security requirement is unmet");
        assertEquals(1, port.disconnectNativeCalls, "must disconnect a link that does not meet the required security");
        assertEquals(1, port.markDisconnectedCalls);
    }

    @Test
    void connectedWithSecurityRequirementMetResolvesGattNormally() {
        FakeDevicePort port = new FakeDevicePort();
        port.wanted = true;
        port.hasNative = true;
        port.nativeConnected = true;
        port.flagConnected = true;
        port.gattResolved = false;
        port.securityUnmet = false; // requirement satisfied (or no requirement)

        DeviceReconciler r = reconciler(port, scanOff(), new MutableClock(START));
        r.reconcile();

        assertEquals(1, port.resolveGattCalls, "a link meeting its security requirement resolves GATT as usual");
        assertEquals(0, port.disconnectNativeCalls);
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
