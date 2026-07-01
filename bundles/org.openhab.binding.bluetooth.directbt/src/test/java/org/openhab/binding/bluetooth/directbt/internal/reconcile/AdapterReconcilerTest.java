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

import org.direct_bt.BTAdapter;
import org.direct_bt.BTMode;
import org.direct_bt.HCIStatusCode;
import org.direct_bt.ScanType;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Regression harness for the {@link AdapterReconciler} — the adapter power + scan reconciler. These lock down
 * the bring-up ladder and scan-recovery decisions that were tuned against live CSR/Realtek controllers, where
 * the wrong order (a blind {@code reset()}, or {@code initialize(...,false)} then a separate power step) left
 * the controller wedged in a power-off state that only a reboot cleared.
 * <p>
 * The native {@link BTAdapter} is a Mockito mock (it is an interface); only the few methods the reconciler
 * calls are stubbed. Cross-reference: {@code docs/directbt-csr-wedge-investigation.md},
 * {@code docs/directbt-stability-fix-inventory.md}, and the CSR bring-up commit 0c65c5ae.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
class AdapterReconcilerTest {

    private static final long START = 1_000_000L;

    // ---------------------------------------------------------------------------------------------
    // inSync: the adapter is only in sync when present, valid, initialized AND powered (when powered is
    // wanted, which it always is here).
    // ---------------------------------------------------------------------------------------------
    @Test
    void inSyncOnlyWhenPresentValidInitializedAndPowered() {
        BTAdapter a = mock(BTAdapter.class);
        when(a.isValid()).thenReturn(true);
        when(a.isInitialized()).thenReturn(true);
        when(a.isPowered()).thenReturn(true);

        AdapterReconciler r = reconciler(a, new MutableClock(START), budget(new MutableClock(START)));
        assertTrue(r.reconcile(), "present+valid+initialized+powered == in sync");
    }

    @Test
    void notInSyncWhenPoweredOff() {
        BTAdapter a = mock(BTAdapter.class);
        when(a.isValid()).thenReturn(true);
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
        when(a.isInitialized()).thenReturn(true);
        when(a.isPowered()).thenReturn(false);
        when(a.setPowered(true)).thenReturn(false); // setPowered fails -> reset fallback
        when(a.reset()).thenReturn(HCIStatusCode.SUCCESS);

        AdapterReconciler r = reconciler(a, new MutableClock(START), budget(new MutableClock(START)));
        r.reconcile();

        InOrder inOrder = inOrder(a);
        inOrder.verify(a).setPowered(true); // tried first
        inOrder.verify(a).reset(); // only after it failed
    }

    // ---------------------------------------------------------------------------------------------
    // Escalation reset is gated by the shared ResetBudget: past the escalate deadline it resets once, but a
    // second escalation within the cooldown must NOT reset again (the CSR reset-storm guard).
    // ---------------------------------------------------------------------------------------------
    @Test
    void escalationResetIsRateLimitedByBudget() {
        BTAdapter a = mock(BTAdapter.class);
        when(a.isValid()).thenReturn(true);
        when(a.isInitialized()).thenReturn(true);
        when(a.isPowered()).thenReturn(false); // permanently off -> stays out of sync -> escalates
        // setPowered "succeeds" at the HCI level so act() never takes the reset() fallback: this isolates the
        // reset() calls to the escalation path, so the assertion measures the budget gate and nothing else.
        when(a.setPowered(true)).thenReturn(true);
        when(a.reset()).thenReturn(HCIStatusCode.SUCCESS);

        MutableClock clock = new MutableClock(START);
        ResetBudget budget = new ResetBudget(logger(), 100_000, clock); // cooldown longer than the whole test
        AdapterReconciler r = reconciler(a, clock, budget);

        r.reconcile(); // out of sync since START
        // Cross the escalate deadline (6s) AND the base backoff so the first escalating tick runs.
        clock.advance(20_000);
        r.reconcile(); // escalates -> reset #1 consumes the budget
        // A second escalation still well within the 100s cooldown must NOT reset again.
        clock.advance(20_000);
        r.reconcile();

        verify(a, times(1)).reset(); // exactly one reset despite two escalations within the cooldown
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

    private static ResetBudget budget(MutableClock clock) {
        return new ResetBudget(logger(), 8000, clock);
    }

    private static Logger logger() {
        return LoggerFactory.getLogger(AdapterReconcilerTest.class);
    }
}
