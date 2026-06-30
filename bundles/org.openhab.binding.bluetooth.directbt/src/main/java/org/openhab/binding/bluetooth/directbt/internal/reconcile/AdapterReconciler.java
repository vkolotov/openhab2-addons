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

import java.util.function.Supplier;

import org.direct_bt.BTAdapter;
import org.direct_bt.BTMode;
import org.direct_bt.DiscoveryPolicy;
import org.direct_bt.HCIStatusCode;
import org.direct_bt.ScanType;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.slf4j.Logger;

/**
 * Reconciles the ADAPTER resource — both of its native fields: <b>power</b> and the <b>LE discovery scan</b>.
 * <p>
 * Scan is NOT a separate resource: it is a field of the same single radio as power, and the two mutually
 * constrain (the controller rejects create-connection while scanning), so they reconcile together against one
 * adapter. They are, however, reconciled in two phases because the scan's desired state is a rollup of the
 * device resources, which must reconcile in between:
 * <ol>
 * <li><b>power</b> — the {@link Reconciler base} {@link #reconcile()}: bring the adapter to powered/present/valid.
 * This is the root of the dependency DAG; while it is not in-sync the device reconcilers are paused. act = power
 * up (initialize if never initialized, else reset+setPowered); escalate (delta persisted) = full reset.</li>
 * <li><b>scan</b> — {@link #reconcileScan(boolean)}, called by the driver AFTER the device reconcilers run (the
 * scan's desired = {@code anyDeviceNeedsDiscovery && noDeviceConnecting}, computed from device state). scan ON
 * only to find a device we still need to (re)connect; OFF the moment any device is establishing a connection
 * (single-radio exclusion) — that handoff is what breaks the discover/connect deadlock. escalate = the
 * {@code [native LE, meta NONE]} desync wedge -> stop/retry; no adapter reset from scan reconciliation.</li>
 * </ol>
 * Power resets funnel through the shared {@link ResetBudget} so recovery attempts are bounded.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
public class AdapterReconciler extends Reconciler<Boolean, AdapterReconciler.Observed> {

    /** Observed native adapter truth (power fields; the scan phase observes the scan fields itself). */
    public static final class Observed {
        public final boolean present;
        public final boolean valid;
        public final boolean powered;
        public final boolean initialized;

        Observed(boolean present, boolean valid, boolean powered, boolean initialized) {
            this.present = present;
            this.valid = valid;
            this.powered = powered;
            this.initialized = initialized;
        }
    }

    private static final long ESCALATE_AFTER_MS = 6000;

    // Scan parameters (were DiscoveryReconciler's).
    private static final short LE_SCAN_INTERVAL = (short) 24;
    private static final short LE_SCAN_WINDOW = (short) 24;
    private static final byte FILTER_POLICY = (byte) 0;
    private static final boolean FILTER_DUP = true;
    private static final int STOP_SETTLE_TRIES = 15;
    private static final long STOP_SETTLE_MS = 100;
    private static final long SCAN_DESYNC_RESET_AFTER_MS = 6000;

    private final Supplier<@Nullable BTAdapter> adapterSupplier;
    private final ResetBudget resetBudget;

    // --- scan field state -----------------------------------------------------------------------
    // Last polled scan truth, used by device reconcilers to gate connectLE (must see scan OFF).
    private volatile boolean scanDiscovering;
    private volatile ScanType scanType = ScanType.NONE;
    // When the scan-desync delta (wanted ON but never engaged) first appeared, for the reset-escalation deadline.
    private long scanOutOfSyncSince;

    public AdapterReconciler(Logger logger, Supplier<@Nullable BTAdapter> adapterSupplier, ResetBudget resetBudget) {
        super("adapter", logger, Boolean.TRUE); // desired = powered
        this.adapterSupplier = adapterSupplier;
        this.resetBudget = resetBudget;
    }

    // --- power phase (base Reconciler) ----------------------------------------------------------

    @Override
    protected Observed observe() {
        BTAdapter a = adapterSupplier.get();
        if (a == null) {
            return new Observed(false, false, false, false);
        }
        boolean valid;
        try {
            valid = a.isValid();
        } catch (RuntimeException e) {
            valid = false;
        }
        return new Observed(true, valid, valid && a.isPowered(), valid && a.isInitialized());
    }

    @Override
    protected boolean inSync(Boolean wantPowered, Observed o) {
        return o.present && o.valid && o.initialized && (!wantPowered || o.powered);
    }

    @Override
    protected void act(Boolean wantPowered, Observed o) {
        BTAdapter a = adapterSupplier.get();
        if (a == null || !o.valid) {
            // Adapter absent/invalid: nothing this reconciler can do; the manager's adapterAdded callback (or a
            // physical re-plug) must supply a fresh handle. Stay out-of-sync so dependents remain paused.
            logger.debug("[reconcile:adapter] no valid adapter handle yet");
            return;
        }
        if (!o.initialized) {
            HCIStatusCode rc = a.initialize(BTMode.DUAL, false);
            logger.debug("[reconcile:adapter] initialize -> {} (powered={} initialized={})", rc, a.isPowered(),
                    a.isInitialized());
            if (rc == HCIStatusCode.SUCCESS && wantPowered && !a.isPowered()) {
                a.setPowered(true);
            }
        } else if (!o.powered) {
            HCIStatusCode rc = a.reset();
            logger.debug("[reconcile:adapter] reset -> {} (powered={} initialized={})", rc, a.isPowered(),
                    a.isInitialized());
            if (rc == HCIStatusCode.SUCCESS && !a.isPowered()) {
                a.setPowered(true);
            }
        }
    }

    @Override
    protected long escalateAfterMillis() {
        return ESCALATE_AFTER_MS;
    }

    @Override
    protected void escalate(Boolean wantPowered, Observed o) {
        BTAdapter a = adapterSupplier.get();
        if (a == null || !o.valid) {
            return;
        }
        if (resetBudget.tryReset("adapter")) {
            HCIStatusCode rc = a.reset();
            logger.warn("[reconcile:adapter] escalation reset -> {} (powered={} initialized={})", rc, a.isPowered(),
                    a.isInitialized());
            if (rc == HCIStatusCode.SUCCESS && !a.isPowered()) {
                a.setPowered(true);
            }
        }
    }

    // --- scan phase -----------------------------------------------------------------------------

    /** @return the adapter's last-polled scan state == OFF, used by device reconcilers to gate connectLE. */
    public boolean isScanOff() {
        return !scanDiscovering && scanType == ScanType.NONE;
    }

    /**
     * Reconcile the adapter's scan field toward {@code scanWanted} (computed by the driver from device state, the
     * rollup of the device resources). Called AFTER the device reconcilers each tick. Observes native scan truth,
     * acts only on a delta, and self-heals the {@code [native LE, meta NONE]} desync via a budgeted reset when the
     * scan is wanted ON but never engages past {@link #SCAN_DESYNC_RESET_AFTER_MS}.
     */
    public void reconcileScan(boolean scanWanted) {
        BTAdapter a = adapterSupplier.get();
        long now = System.currentTimeMillis();
        if (a == null) {
            scanDiscovering = false;
            scanType = ScanType.NONE;
            scanOutOfSyncSince = 0;
            return;
        }
        // Observe native scan truth.
        boolean discovering = a.isDiscovering();
        ScanType type = a.getCurrentScanType();
        scanDiscovering = discovering;
        scanType = type;

        if (scanWanted == discovering) {
            scanOutOfSyncSince = 0; // in sync
            return;
        }
        // Delta: track how long it has persisted (for the scan-desync escalation).
        if (scanOutOfSyncSince == 0) {
            scanOutOfSyncSince = now;
        }
        try {
            if (!scanWanted) {
                if (discovering || type != ScanType.NONE) {
                    a.stopDiscovery();
                    logger.debug("[reconcile:adapter:scan] stop -> discovering={} scanType={}", a.isDiscovering(),
                            a.getCurrentScanType());
                }
                return;
            }
            // scanWanted ON but not discovering. If startDiscovery keeps being accepted without the adapter moving
            // to a discovering state, do not reset the controller: field testing showed resets can wedge CSR/Realtek
            // adapters. Stop once and let the periodic loop retry from observed state.
            if (now - scanOutOfSyncSince > SCAN_DESYNC_RESET_AFTER_MS && type == ScanType.NONE) {
                logger.warn("[reconcile:adapter:scan] scan desync persisted; stopping scan without adapter reset");
                a.stopDiscovery();
                scanOutOfSyncSince = now;
                return;
            }
            // Ensure we start from a clean stopped state (avoid the [native LE, meta NONE] split), then start.
            if (type != ScanType.NONE) {
                a.stopDiscovery();
                for (int i = 0; i < STOP_SETTLE_TRIES && a.getCurrentScanType() != ScanType.NONE; i++) {
                    Thread.sleep(STOP_SETTLE_MS);
                }
            }
            HCIStatusCode res = a.startDiscovery(null, DiscoveryPolicy.PAUSE_CONNECTED_UNTIL_READY, true,
                    LE_SCAN_INTERVAL, LE_SCAN_WINDOW, FILTER_POLICY, FILTER_DUP);
            logger.debug("[reconcile:adapter:scan] start -> {} (discovering={} scanType={})", res, a.isDiscovering(),
                    a.getCurrentScanType());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Reset the cached scan-field state (used when the driver pauses the adapter / the handle goes away). */
    public void resetScanState() {
        scanDiscovering = false;
        scanType = ScanType.NONE;
        scanOutOfSyncSince = 0;
    }
}
