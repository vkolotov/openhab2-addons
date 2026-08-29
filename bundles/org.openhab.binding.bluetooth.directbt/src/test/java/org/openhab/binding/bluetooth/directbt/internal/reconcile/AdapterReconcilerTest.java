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
import static org.mockito.Mockito.*;
import static org.openhab.binding.bluetooth.directbt.internal.reconcile.ReconcileTestSupport.*;

import java.util.concurrent.atomic.AtomicBoolean;

import org.direct_bt.BTAdapter;
import org.direct_bt.BTMode;
import org.direct_bt.HCIStatusCode;
import org.direct_bt.ScanType;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.adapter.*;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.device.*;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.effect.*;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.event.*;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.port.*;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.procedure.*;

/**
 * Regression harness for the {@link AdapterReconciler} — the adapter power + scan reconciler. These lock down
 * the bring-up ladder and scan-recovery decisions that were tuned against live CSR/Realtek controllers, where
 * the wrong order (a blind {@code reset()}, or {@code initialize(...,false)} then a separate power step) left
 * the controller wedged in a power-off state that only a reboot cleared.
 * <p>
 * The native {@link BTAdapter} is a Mockito mock (it is an interface); only the few methods the reconciler
 * calls are stubbed.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
class AdapterReconcilerTest {

    // ---------------------------------------------------------------------------------------------
    // inSync: the adapter is only in sync when present, valid, initialized AND powered (when powered is
    // wanted, which it always is here).
    // ---------------------------------------------------------------------------------------------
    @Test
    void inSyncOnlyWhenPresentValidInitializedAndPowered() {
        BTAdapter a = mock(BTAdapter.class);
        when(a.isValid()).thenReturn(true);
        when(a.isControllerHealthy()).thenReturn(true);
        when(a.isInitialized()).thenReturn(true);
        when(a.isPowered()).thenReturn(true);

        AdapterReconciler r = reconciler(a, new MutableClock(START), budget(new MutableClock(START)));
        assertTrue(r.reconcile(), "present+valid+initialized+powered == in sync");
    }

    // ---------------------------------------------------------------------------------------------
    // Controller hardware fault. A controller that stops accepting HCI commands leaves isValid()/isPowered()
    // reading true, so without an explicit liveness check the adapter looks perfectly healthy while every
    // device on it is offline — the state that left a wedged dongle unrecovered for 76 minutes. The fault must
    // therefore break in-sync, and recovery must use a FORCED reset: plain reset()/setPowered() travel the same
    // dead command channel.
    // ---------------------------------------------------------------------------------------------
    @Test
    void controllerHardwareFaultIsNotInSyncEvenWhenValidAndPowered() {
        BTAdapter a = mock(BTAdapter.class);
        when(a.isValid()).thenReturn(true);
        when(a.isInitialized()).thenReturn(true);
        when(a.isPowered()).thenReturn(true);
        when(a.isControllerHealthy()).thenReturn(false);

        AdapterReconciler r = reconciler(a, new MutableClock(START), budget(new MutableClock(START)));
        assertFalse(r.reconcile(), "faulted controller is never in sync, however healthy the cached state looks");
    }

    @Test
    void controllerHardwareFaultIssuesForcedReset() {
        BTAdapter a = mock(BTAdapter.class);
        when(a.isValid()).thenReturn(true);
        when(a.isInitialized()).thenReturn(true);
        when(a.isPowered()).thenReturn(true);
        when(a.isControllerHealthy()).thenReturn(false);
        when(a.reset(anyBoolean())).thenReturn(HCIStatusCode.SUCCESS);

        AdapterReconciler r = reconciler(a, new MutableClock(START), budget(new MutableClock(START)));
        r.reconcile();

        verify(a).reset(true);
        // The command-channel rungs must NOT be attempted: they cannot reach a controller that has stopped
        // accepting commands, and trying them only burns the recovery window.
        verify(a, never()).reset(false);
        verify(a, never()).initialize(any(), anyBoolean());
    }

    @Test
    void forcedResetOutcomeIsReported() {
        BTAdapter a = mock(BTAdapter.class);
        when(a.isValid()).thenReturn(true);
        when(a.isInitialized()).thenReturn(true);
        when(a.isPowered()).thenReturn(true);
        when(a.isControllerHealthy()).thenReturn(false);
        when(a.reset(anyBoolean())).thenReturn(HCIStatusCode.SUCCESS);

        AtomicBoolean reported = new AtomicBoolean(false);
        AdapterReconciler r = reconciler(a, new MutableClock(START), budget(new MutableClock(START)));
        r.setForcedResetListener(reported::set);
        r.reconcile();

        assertTrue(reported.get(), "a successful forced reset is reported, so the metric can answer 'did it work?'");
    }

    @Test
    void invalidAdapterStillForcesResetOnEscalation() {
        // A wedged controller can also present as invalid. Escalation previously returned early on !valid,
        // leaving nothing to drive recovery; it must now force through instead.
        BTAdapter a = mock(BTAdapter.class);
        when(a.isValid()).thenReturn(false);
        when(a.isControllerHealthy()).thenReturn(true);
        when(a.reset(anyBoolean())).thenReturn(HCIStatusCode.SUCCESS);

        MutableClock clock = new MutableClock(START);
        AdapterReconciler r = reconciler(a, clock, budget(clock));
        r.reconcile();
        clock.advance(20_000); // past the 6s escalate deadline and the base backoff
        r.reconcile();

        verify(a).reset(true);
    }

    @Test
    void notInSyncWhenPoweredOff() {
        BTAdapter a = mock(BTAdapter.class);
        when(a.isValid()).thenReturn(true);
        when(a.isControllerHealthy()).thenReturn(true);
        when(a.isInitialized()).thenReturn(true);
        when(a.isPowered()).thenReturn(false); // off
        when(a.setPowered(true)).thenReturn(true);

        AdapterReconciler r = reconciler(a, new MutableClock(START), budget(new MutableClock(START)));
        assertFalse(r.reconcile(), "powered-off adapter is not in sync");
    }

    // ---------------------------------------------------------------------------------------------
    // CSR bring-up (commit 0c65c5ae): an uninitialized adapter must be brought up with
    // initialize(DUAL, wantPowered=TRUE) — powering on AS PART of initialize. The earlier
    // initialize(DUAL,false) + a separate power step stalled CSR8510 bring-up.
    // ---------------------------------------------------------------------------------------------
    @Test
    void uninitializedAdapterIsInitializedDualWithPowerOn() {
        BTAdapter a = mock(BTAdapter.class);
        when(a.isValid()).thenReturn(true);
        when(a.isControllerHealthy()).thenReturn(true);
        when(a.isInitialized()).thenReturn(false); // never initialized
        when(a.isPowered()).thenReturn(false);
        when(a.initialize(any(), anyBoolean())).thenReturn(HCIStatusCode.SUCCESS);

        AdapterReconciler r = reconciler(a, new MutableClock(START), budget(new MutableClock(START)));
        r.reconcile();

        verify(a).initialize(BTMode.DUAL, true);
        verify(a, never()).initialize(BTMode.DUAL, false);
    }

    // ---------------------------------------------------------------------------------------------
    // Initialized-but-off: try setPowered(true) FIRST; only fall back to reset() if setPowered fails. A blind
    // reset can wedge the CSR (the whole reason for this ordering).
    // ---------------------------------------------------------------------------------------------
    @Test
    void initializedButOffTriesSetPoweredBeforeReset() {
        BTAdapter a = mock(BTAdapter.class);
        when(a.isValid()).thenReturn(true);
        when(a.isControllerHealthy()).thenReturn(true);
        when(a.isInitialized()).thenReturn(true);
        when(a.isPowered()).thenReturn(false);
        when(a.setPowered(true)).thenReturn(true); // setPowered succeeds

        AdapterReconciler r = reconciler(a, new MutableClock(START), budget(new MutableClock(START)));
        r.reconcile();

        verify(a).setPowered(true);
        verify(a, never()).reset(); // a successful setPowered must NOT be followed by a (wedge-prone) reset
    }

    @Test
    void initializedButOffFallsBackToResetOnlyWhenSetPoweredFails() {
        BTAdapter a = mock(BTAdapter.class);
        when(a.isValid()).thenReturn(true);
        when(a.isControllerHealthy()).thenReturn(true);
        when(a.isInitialized()).thenReturn(true);
        when(a.isPowered()).thenReturn(false);
        when(a.setPowered(true)).thenReturn(false); // setPowered fails -> reset fallback
        when(a.reset(anyBoolean())).thenReturn(HCIStatusCode.SUCCESS);

        AdapterReconciler r = reconciler(a, new MutableClock(START), budget(new MutableClock(START)));
        r.reconcile();

        InOrder inOrder = inOrder(a);
        inOrder.verify(a).setPowered(true); // tried first
        // Unforced: the controller is healthy here, so this is the ordinary power-up fallback, not fault recovery.
        inOrder.verify(a).reset(false); // only after it failed
    }

    // ---------------------------------------------------------------------------------------------
    // Escalation reset is gated by the shared ResetBudget: past the escalate deadline it resets once, but a
    // second escalation within the cooldown must NOT reset again (the CSR reset-storm guard).
    // ---------------------------------------------------------------------------------------------
    @Test
    void escalationResetIsRateLimitedByBudget() {
        BTAdapter a = mock(BTAdapter.class);
        when(a.isValid()).thenReturn(true);
        when(a.isControllerHealthy()).thenReturn(true);
        when(a.isInitialized()).thenReturn(true);
        when(a.isPowered()).thenReturn(false); // permanently off -> stays out of sync -> escalates
        // setPowered "succeeds" at the HCI level so act() never takes the reset() fallback: this isolates the
        // reset() calls to the escalation path, so the assertion measures the budget gate and nothing else.
        when(a.setPowered(true)).thenReturn(true);
        when(a.reset(anyBoolean())).thenReturn(HCIStatusCode.SUCCESS);

        MutableClock clock = new MutableClock(START);
        ResetBudget budget = new ResetBudget(100_000, clock); // cooldown longer than the whole test
        AdapterReconciler r = reconciler(a, clock, budget);

        r.reconcile(); // out of sync since START
        // Cross the escalate deadline (6s) AND the base backoff so the first escalating tick runs.
        clock.advance(20_000);
        r.reconcile(); // escalates -> reset #1 consumes the budget
        // A second escalation still well within the 100s cooldown must NOT reset again.
        clock.advance(20_000);
        r.reconcile();

        verify(a, times(1)).reset(anyBoolean()); // exactly one reset despite two escalations within the cooldown
    }

    // ---------------------------------------------------------------------------------------------
    // Scan-desync recovery (CSR/Realtek wedge fix): when a scan is wanted ON but the controller accepts
    // startDiscovery yet never actually discovers, the reconciler must STOP the scan and retry — it must
    // NOT reset the adapter (field testing showed resets wedge CSR/Realtek).
    // ---------------------------------------------------------------------------------------------
    @Test
    void persistentScanDesyncStopsScanWithoutResettingAdapter() {
        BTAdapter a = mock(BTAdapter.class);
        // scan wanted ON, but the adapter never engages: discovering=false, scanType=NONE throughout.
        when(a.isDiscovering()).thenReturn(false);
        when(a.getCurrentScanType()).thenReturn(ScanType.NONE);

        MutableClock clock = new MutableClock(START);
        AdapterReconciler r = reconciler(a, clock, budget(clock));

        r.reconcileScan(true); // first sight of the desync
        clock.advance(7000); // past SCAN_DESYNC_RESET_AFTER_MS (6000)
        r.reconcileScan(true);

        verify(a, never()).reset(); // the whole point: recover by stopping, not by resetting a wedge-prone radio
        verify(a, atLeastOnce()).stopDiscovery();
    }

    @Test
    void scanNotWantedButActiveIsStopped() {
        BTAdapter a = mock(BTAdapter.class);
        when(a.isDiscovering()).thenReturn(true);
        when(a.getCurrentScanType()).thenReturn(ScanType.LE);

        AdapterReconciler r = reconciler(a, new MutableClock(START), budget(new MutableClock(START)));
        r.reconcileScan(false);

        verify(a).stopDiscovery();
    }

    // ---------------------------------------------------------------------------------------------
    // Discovery restart. Direct-BT only flushes its native discovered-device list in startDiscovery(), and the
    // reconciler otherwise leaves one session running, so the list grows for the life of the process as private
    // addresses rotate. restartDiscovery() stops an active scan so the next reconcile re-starts it, doing the flush.
    // ---------------------------------------------------------------------------------------------
    @Test
    void restartDiscoveryStopsAnActiveScanSoTheNextReconcileRestartsIt() {
        BTAdapter a = mock(BTAdapter.class);
        when(a.isDiscovering()).thenReturn(true);
        when(a.getCurrentScanType()).thenReturn(ScanType.LE);

        AdapterReconciler r = reconciler(a, new MutableClock(START), budget(new MutableClock(START)));
        assertTrue(r.restartDiscovery(), "an active scan was stopped");

        verify(a).stopDiscovery();
    }

    @Test
    void restartDiscoveryIsANoOpWhenTheScanIsAlreadyOff() {
        // Nothing to stop: the next reconcile starts a fresh session (and thus flushes) regardless.
        BTAdapter a = mock(BTAdapter.class);
        when(a.isDiscovering()).thenReturn(false);
        when(a.getCurrentScanType()).thenReturn(ScanType.NONE);

        AdapterReconciler r = reconciler(a, new MutableClock(START), budget(new MutableClock(START)));
        assertFalse(r.restartDiscovery(), "no active scan to stop");

        verify(a, never()).stopDiscovery();
    }

    // ---------------------------------------------------------------------------------------------
    // isScanOff() gates connectLE in the DEVICE phase, which runs BEFORE the next scan-phase observation.
    // The cached scan state must therefore be refreshed in the SAME call that changes the scan, or the next
    // tick's devices act on a one-tick-stale answer and fire connectLE into an active scan (the ~300 ms
    // COMMAND_DISALLOWED race seen in the field).
    // ---------------------------------------------------------------------------------------------
    @Test
    void scanStartRefreshesTheConnectGateCacheImmediately() {
        BTAdapter a = mock(BTAdapter.class);
        AtomicBoolean scanning = new AtomicBoolean(false);
        when(a.isDiscovering()).thenAnswer(inv -> scanning.get());
        when(a.getCurrentScanType()).thenAnswer(inv -> scanning.get() ? ScanType.LE : ScanType.NONE);
        when(a.startDiscovery(any(), any(), anyBoolean(), anyShort(), anyShort(), anyByte(), anyBoolean()))
                .thenAnswer(inv -> {
                    scanning.set(true);
                    return HCIStatusCode.SUCCESS;
                });

        AdapterReconciler r = reconciler(a, new MutableClock(START), budget(new MutableClock(START)));
        assertTrue(r.isScanOff());

        r.reconcileScan(true); // starts the scan

        assertFalse(r.isScanOff(), "the gate must see the scan in the same tick it starts");
    }

    @Test
    void scanStopRefreshesTheConnectGateCacheImmediately() {
        BTAdapter a = mock(BTAdapter.class);
        AtomicBoolean scanning = new AtomicBoolean(true);
        when(a.isDiscovering()).thenAnswer(inv -> scanning.get());
        when(a.getCurrentScanType()).thenAnswer(inv -> scanning.get() ? ScanType.LE : ScanType.NONE);
        doAnswer(inv -> {
            scanning.set(false);
            return HCIStatusCode.SUCCESS;
        }).when(a).stopDiscovery();

        AdapterReconciler r = reconciler(a, new MutableClock(START), budget(new MutableClock(START)));
        r.reconcileScan(false); // stops the scan

        assertTrue(r.isScanOff(), "a stop must open the connect gate the same tick, not one tick later");
    }

    // ---------------------------------------------------------------------------------------------
    // Scan is DUTY-CYCLED (window < interval), not 100% duty. A window == interval scan keeps the radio busy
    // the whole time and starves a connected device's ACL, so once DiscoveryPolicy.PAUSE_CONNECTED_UNTIL_READY
    // resumes the scan the connected device drops (supervision timeout). With two wanted devices one is always
    // still discovering while the other is connected -> perpetual scan -> both drop in a loop (RTL8761BU).
    // This locks the window strictly below the interval so the fix can't regress to the full-duty scan.
    // ---------------------------------------------------------------------------------------------
    @Test
    void startedScanIsDutyCycledSoConnectedDevicesSurvive() {
        BTAdapter a = mock(BTAdapter.class);
        // scan wanted ON, adapter not yet discovering with no lingering scan -> reconciler issues startDiscovery.
        when(a.isDiscovering()).thenReturn(false);
        when(a.getCurrentScanType()).thenReturn(ScanType.NONE);
        when(a.startDiscovery(any(), any(), anyBoolean(), anyShort(), anyShort(), anyByte(), anyBoolean()))
                .thenReturn(HCIStatusCode.SUCCESS);

        AdapterReconciler r = reconciler(a, new MutableClock(START), budget(new MutableClock(START)));
        r.reconcileScan(true);

        ArgumentCaptor<Short> interval = ArgumentCaptor.forClass(Short.class);
        ArgumentCaptor<Short> window = ArgumentCaptor.forClass(Short.class);
        // startDiscovery(gattServer, policy, active, le_scan_interval, le_scan_window, filter_policy, filter_dup)
        verify(a).startDiscovery(any(), any(), anyBoolean(), interval.capture(), window.capture(), anyByte(),
                anyBoolean());

        short iv = interval.getValue();
        short win = window.getValue();
        // Both are valid LE scan params (0.625ms units, spec range 0x0004..0x4000).
        assertTrue(win >= 0x0004 && win <= 0x4000, "scan window in spec range");
        assertTrue(iv >= 0x0004 && iv <= 0x4000, "scan interval in spec range");
        // The fix: window strictly below interval leaves radio gaps for a connected device's ACL. A full-duty
        // scan (window == interval) is the exact regression that dropped concurrent devices.
        assertTrue(win < iv, "scan must be duty-cycled (window < interval) so connections survive; was window=" + win
                + " interval=" + iv);
    }

    // ---------------------------------------------------------------------------------------------
    // isScanOff() is the gate the device reconcilers read before issuing connectLE (the controller rejects
    // create-connection while scanning). It must report OFF only when neither discovering nor a lingering
    // scan type remains.
    // ---------------------------------------------------------------------------------------------
    @Test
    void isScanOffReflectsPolledScanState() {
        BTAdapter a = mock(BTAdapter.class);
        AdapterReconciler r = reconciler(a, new MutableClock(START), budget(new MutableClock(START)));

        when(a.isDiscovering()).thenReturn(true);
        when(a.getCurrentScanType()).thenReturn(ScanType.LE);
        r.reconcileScan(true); // poll: scan is running
        assertFalse(r.isScanOff(), "a running scan is not OFF");

        when(a.isDiscovering()).thenReturn(false);
        when(a.getCurrentScanType()).thenReturn(ScanType.NONE);
        r.reconcileScan(false); // poll: scan stopped and settled to NONE
        assertTrue(r.isScanOff(), "not discovering and scanType NONE == OFF");
    }

    @Test
    void resetScanStateClearsCachedScanTruthToOff() {
        BTAdapter a = mock(BTAdapter.class);
        when(a.isDiscovering()).thenReturn(true);
        when(a.getCurrentScanType()).thenReturn(ScanType.LE);

        AdapterReconciler r = reconciler(a, new MutableClock(START), budget(new MutableClock(START)));
        r.reconcileScan(true); // caches discovering=true
        assertFalse(r.isScanOff());

        r.resetScanState(); // driver pauses the adapter / handle goes away
        assertTrue(r.isScanOff(), "after resetScanState the cached scan state reads OFF");
    }

    // ---------------------------------------------------------------------------------------------
    // observe() must treat a native isValid() throw as "invalid" (not propagate), so a controller that
    // throws during polling degrades to out-of-sync rather than crashing the tick.
    // ---------------------------------------------------------------------------------------------
    @Test
    void observeTreatsIsValidThrowAsInvalidAndNotInSync() {
        BTAdapter a = mock(BTAdapter.class);
        when(a.isValid()).thenThrow(new RuntimeException("native poll blew up"));

        AdapterReconciler r = reconciler(a, new MutableClock(START), budget(new MutableClock(START)));
        assertFalse(r.reconcile(), "a throwing isValid() degrades to invalid/out-of-sync, not a crashed tick");
    }

    // --- helpers ---------------------------------------------------------------------------------

    private static AdapterReconciler reconciler(@Nullable BTAdapter adapter, MutableClock clock, ResetBudget budget) {
        return new AdapterReconciler(logger(), () -> adapter, budget, clock);
    }
}
