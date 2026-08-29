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
package org.openhab.binding.bluetooth.directbt.internal.reconcile.adapter;

import java.time.Clock;
import java.util.function.Supplier;

import org.direct_bt.BTAdapter;
import org.direct_bt.DiscoveryPolicy;
import org.direct_bt.HCIStatusCode;
import org.direct_bt.ScanType;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.slf4j.Logger;

/**
 * Adapter scan procedure: reconcile the LE discovery field of the adapter.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
public final class AdapterScanProcedure {
    // Units of 0.625ms. DUTY-CYCLED so the scan can coexist with a live connection. With window == interval the
    // radio scans 100% of the time and leaves no slots for a connected device's ACL; with two wanted devices one
    // is often still hunting while another is connected, so a full-duty scan can drop both in a loop.
    public static final short DEFAULT_LE_SCAN_INTERVAL = (short) 144; // 90ms
    public static final short DEFAULT_LE_SCAN_WINDOW = (short) 24; // 15ms (~17% duty)

    private static final byte FILTER_POLICY = (byte) 0;
    private static final int STOP_SETTLE_TRIES = 15;
    private static final long STOP_SETTLE_MS = 100;
    private static final long SCAN_DESYNC_RESET_AFTER_MS = 6000;

    private final Logger logger;
    private final Supplier<@Nullable BTAdapter> adapterSupplier;
    private final Clock clock;

    private volatile short leScanInterval = DEFAULT_LE_SCAN_INTERVAL;
    private volatile short leScanWindow = DEFAULT_LE_SCAN_WINDOW;
    // Default OFF: fixed-address devices are otherwise reported once per scan session, so a Thing that was not
    // ready at that single deviceFound moment may never get another native handle while scanning continues.
    private volatile boolean filterDuplicates = false;

    // Last polled scan truth, used by device reconcilers to gate connectLE (must see scan OFF).
    private volatile boolean scanDiscovering;
    private volatile ScanType scanType = ScanType.NONE;
    // When the scan-desync delta (wanted ON but never engaged) first appeared.
    private long scanOutOfSyncSince;

    public AdapterScanProcedure(Logger logger, Supplier<@Nullable BTAdapter> adapterSupplier, Clock clock) {
        this.logger = logger;
        this.adapterSupplier = adapterSupplier;
        this.clock = clock;
    }

    /**
     * Applies bridge-configured LE scan parameters (0.625 ms slots). Clamped to the BT-spec range
     * [4, 16384]; the window is additionally clamped to the interval (window > interval is invalid).
     * Takes effect on the next scan (re)start — an already-running scan is not restarted.
     */
    public void setScanParameters(int intervalSlots, int windowSlots, boolean filterDuplicates) {
        int interval = Math.max(4, Math.min(16384, intervalSlots));
        int window = Math.max(4, Math.min(interval, windowSlots));
        if (interval != intervalSlots || window != windowSlots) {
            logger.warn("[reconcile:adapter:scan] scan parameters clamped: interval {} -> {}, window {} -> {}",
                    intervalSlots, interval, windowSlots, window);
        }
        this.leScanInterval = (short) interval;
        this.leScanWindow = (short) window;
        this.filterDuplicates = filterDuplicates;
        logger.debug(
                "[reconcile:adapter:scan] scan parameters set: interval={} slots, window={} slots (~{}% duty), filterDuplicates={}",
                interval, window, (window * 100) / interval, filterDuplicates);
    }

    /** @return the adapter's last-polled scan state == OFF, used by device reconcilers to gate connectLE. */
    public boolean isScanOff() {
        return !scanDiscovering && scanType == ScanType.NONE;
    }

    /**
     * Reconcile the adapter's scan field toward {@code scanWanted}. Called AFTER the device reconcilers each
     * tick because scan intent is a rollup of device state.
     */
    public void reconcile(boolean scanWanted) {
        BTAdapter a = adapterSupplier.get();
        long now = clock.millis();
        if (a == null) {
            resetState();
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
                stopIfActive(a, discovering, type);
                return;
            }
            startWantedScan(a, type, now);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Stop an active scan so the next {@link #reconcile(boolean)} starts a fresh discovery session.
     * <p>
     * Used to flush Direct-BT's native discovered-device list, which only {@code startDiscovery()} clears. A no-op
     * if the scan is already off, in which case the next reconcile starts a fresh session anyway.
     *
     * @return true if an active scan was stopped
     */
    public boolean restartDiscovery() {
        BTAdapter a = adapterSupplier.get();
        if (a == null) {
            return false;
        }
        boolean discovering = a.isDiscovering();
        ScanType type = a.getCurrentScanType();
        if (!discovering && type == ScanType.NONE) {
            return false;
        }
        stopIfActive(a, discovering, type);
        // Force the next reconcile to see a delta and re-issue startDiscovery(), which does the flush.
        scanOutOfSyncSince = 0;
        return true;
    }

    /** Reset the cached scan-field state (used when the driver pauses the adapter / the handle goes away). */
    public void resetState() {
        scanDiscovering = false;
        scanType = ScanType.NONE;
        scanOutOfSyncSince = 0;
    }

    private void stopIfActive(BTAdapter a, boolean discovering, ScanType type) {
        if (discovering || type != ScanType.NONE) {
            a.stopDiscovery();
            // Refresh the cached fields NOW: isScanOff() gates connectLE in the device phase, which runs
            // BEFORE the next scan-phase observation.
            scanDiscovering = a.isDiscovering();
            scanType = a.getCurrentScanType();
            logger.debug("[reconcile:adapter:scan] stop -> discovering={} scanType={}", scanDiscovering, scanType);
        }
    }

    private void startWantedScan(BTAdapter a, ScanType type, long now) throws InterruptedException {
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
        HCIStatusCode res = a.startDiscovery(null, DiscoveryPolicy.PAUSE_CONNECTED_UNTIL_READY, true, leScanInterval,
                leScanWindow, FILTER_POLICY, filterDuplicates);
        // Same cache refresh as the stop branch: without it, isScanOff() answers "off" for one more tick
        // after the scan started, and a connect-ready device fires connectLE into the active scan.
        scanDiscovering = a.isDiscovering();
        scanType = a.getCurrentScanType();
        logger.debug("[reconcile:adapter:scan] start -> {} (discovering={} scanType={})", res, scanDiscovering,
                scanType);
    }
}
