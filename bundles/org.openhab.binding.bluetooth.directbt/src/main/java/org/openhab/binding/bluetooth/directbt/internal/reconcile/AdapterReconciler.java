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

import java.time.Clock;
import java.util.function.Supplier;

import org.direct_bt.BTAdapter;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.adapter.AdapterPowerProcedure;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.adapter.AdapterScanProcedure;
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
public class AdapterReconciler extends Reconciler<Boolean, AdapterPowerProcedure.Observed> {
    private static final long ESCALATE_AFTER_MS = 6000;

    public static final short DEFAULT_LE_SCAN_INTERVAL = AdapterScanProcedure.DEFAULT_LE_SCAN_INTERVAL;
    public static final short DEFAULT_LE_SCAN_WINDOW = AdapterScanProcedure.DEFAULT_LE_SCAN_WINDOW;

    private final AdapterPowerProcedure powerProcedure;
    private final AdapterScanProcedure scanProcedure;

    public AdapterReconciler(Logger logger, Supplier<@Nullable BTAdapter> adapterSupplier, ResetBudget resetBudget) {
        this(logger, adapterSupplier, resetBudget, Clock.systemUTC());
    }

    public AdapterReconciler(Logger logger, Supplier<@Nullable BTAdapter> adapterSupplier, ResetBudget resetBudget,
            Clock clock) {
        super("adapter", logger, Boolean.TRUE, clock); // desired = powered
        this.powerProcedure = new AdapterPowerProcedure(logger, adapterSupplier, resetBudget);
        this.scanProcedure = new AdapterScanProcedure(logger, adapterSupplier, clock);
    }

    /**
     * Applies bridge-configured LE scan parameters (0.625 ms slots). Clamped to the BT-spec range
     * [4, 16384]; the window is additionally clamped to the interval (window > interval is invalid).
     * Takes effect on the next scan (re)start — an already-running scan is not restarted.
     */
    public void setScanParameters(int intervalSlots, int windowSlots, boolean filterDuplicates) {
        scanProcedure.setScanParameters(intervalSlots, windowSlots, filterDuplicates);
    }

    /** Observe the outcome of forced resets issued for a controller hardware fault. */
    public void setForcedResetListener(AdapterPowerProcedure.ForcedResetListener listener) {
        powerProcedure.setForcedResetListener(listener);
    }

    // --- power phase (base Reconciler) ----------------------------------------------------------

    @Override
    protected AdapterPowerProcedure.Observed observe() {
        return powerProcedure.observe();
    }

    @Override
    protected boolean inSync(Boolean wantPowered, AdapterPowerProcedure.Observed o) {
        return powerProcedure.inSync(wantPowered, o);
    }

    @Override
    protected void act(Boolean wantPowered, AdapterPowerProcedure.Observed o) {
        powerProcedure.act(wantPowered, o);
    }

    @Override
    protected long escalateAfterMillis() {
        return ESCALATE_AFTER_MS;
    }

    @Override
    protected void escalate(Boolean wantPowered, AdapterPowerProcedure.Observed o) {
        powerProcedure.escalate(wantPowered, o);
    }

    // --- scan phase -----------------------------------------------------------------------------

    /** @return the adapter's last-polled scan state == OFF, used by device reconcilers to gate connectLE. */
    public boolean isScanOff() {
        return scanProcedure.isScanOff();
    }

    /**
     * Reconcile the adapter's scan field toward {@code scanWanted} (computed by the driver from device state, the
     * rollup of the device resources). Called AFTER the device reconcilers each tick. Observes native scan truth,
     * acts only on a delta, and self-heals the {@code [native LE, meta NONE]} desync by stopping and retrying
     * discovery without resetting the adapter.
     */
    public void reconcileScan(boolean scanWanted) {
        scanProcedure.reconcile(scanWanted);
    }

    /**
     * Stop an active scan so the next {@link #reconcileScan(boolean)} starts a fresh discovery session, flushing
     * Direct-BT's native discovered-device list.
     *
     * @return true if an active scan was stopped
     */
    public boolean restartDiscovery() {
        return scanProcedure.restartDiscovery();
    }

    /** Reset the cached scan-field state (used when the driver pauses the adapter / the handle goes away). */
    public void resetScanState() {
        scanProcedure.resetState();
    }
}
