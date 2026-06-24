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

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.direct_bt.BTAdapter;
import org.direct_bt.DiscoveryPolicy;
import org.direct_bt.HCIStatusCode;
import org.direct_bt.ScanType;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.slf4j.Logger;

/**
 * Reconciles the adapter's LE discovery scan (design table row 3). The desired scan state is computed each tick
 * from the device reconcilers, NOT remembered:
 *
 * <pre>
 * scanWanted = anyWantedDeviceUnconnected &amp;&amp; noDeviceCurrentlyConnecting
 * </pre>
 *
 * i.e. scan ON only to find a device we still need to (re)connect, and OFF the moment any device is establishing
 * a connection (the controller rejects create-connection while scanning) or all wanted devices are connected
 * (running a scan concurrently with a live ACL is the suspected single-radio drop cause).
 * <p>
 * Observed = polled native truth ({@link BTAdapter#isDiscovering()} / {@link BTAdapter#getCurrentScanType()}),
 * never the {@code startDiscovery()} return-code. escalate = the desync wedge where startDiscovery reported
 * accepted but the scan never engaged ({@code [native LE, meta NONE]}) -> adapter reset via the shared budget.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
public class DiscoveryReconciler extends Reconciler<Boolean, DiscoveryReconciler.Observed> {

    /** Observed native scan truth. */
    public static final class Observed {
        public final boolean discovering;
        public final ScanType scanType;

        Observed(boolean discovering, ScanType scanType) {
            this.discovering = discovering;
            this.scanType = scanType;
        }
    }

    private static final short LE_SCAN_INTERVAL = (short) 24;
    private static final short LE_SCAN_WINDOW = (short) 24;
    private static final byte FILTER_POLICY = (byte) 0;
    private static final boolean FILTER_DUP = true;
    private static final int STOP_SETTLE_TRIES = 15;
    private static final long STOP_SETTLE_MS = 100;
    private static final long ESCALATE_AFTER_MS = 6000;

    private final Supplier<@Nullable BTAdapter> adapterSupplier;
    private final BooleanSupplier scanWantedSupplier;
    private final ResetBudget resetBudget;

    public DiscoveryReconciler(Logger logger, Supplier<@Nullable BTAdapter> adapterSupplier,
            BooleanSupplier scanWantedSupplier, ResetBudget resetBudget) {
        super("discovery", logger, Boolean.FALSE);
        this.adapterSupplier = adapterSupplier;
        this.scanWantedSupplier = scanWantedSupplier;
        this.resetBudget = resetBudget;
    }

    /** @return the adapter's polled scan state == OFF, used by device reconcilers to gate connectLE. */
    public boolean isScanOff() {
        Observed o = observed;
        return o != null && !o.discovering && o.scanType == ScanType.NONE;
    }

    @Override
    protected Observed observe() {
        BTAdapter a = adapterSupplier.get();
        if (a == null) {
            return new Observed(false, ScanType.NONE);
        }
        return new Observed(a.isDiscovering(), a.getCurrentScanType());
    }

    @Override
    protected boolean inSync(Boolean unusedDesired, Observed o) {
        boolean scanWanted = scanWantedSupplier.getAsBoolean();
        // Refresh desired for visibility/logging.
        setDesired(scanWanted);
        return scanWanted == o.discovering;
    }

    @Override
    protected void act(Boolean unusedDesired, Observed o) {
        BTAdapter a = adapterSupplier.get();
        if (a == null) {
            return;
        }
        boolean scanWanted = scanWantedSupplier.getAsBoolean();
        try {
            if (!scanWanted) {
                if (o.discovering || o.scanType != ScanType.NONE) {
                    a.stopDiscovery();
                    logger.debug("[reconcile:discovery] stop -> discovering={} scanType={}", a.isDiscovering(),
                            a.getCurrentScanType());
                }
                return;
            }
            // scanWanted: ensure we start from a clean stopped state (avoid the [native LE, meta NONE] split),
            // then start.
            if (a.getCurrentScanType() != ScanType.NONE) {
                a.stopDiscovery();
                for (int i = 0; i < STOP_SETTLE_TRIES && a.getCurrentScanType() != ScanType.NONE; i++) {
                    Thread.sleep(STOP_SETTLE_MS);
                }
            }
            HCIStatusCode res = a.startDiscovery(null, DiscoveryPolicy.PAUSE_CONNECTED_UNTIL_READY, true,
                    LE_SCAN_INTERVAL, LE_SCAN_WINDOW, FILTER_POLICY, FILTER_DUP);
            logger.debug("[reconcile:discovery] start -> {} (discovering={} scanType={})", res, a.isDiscovering(),
                    a.getCurrentScanType());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    protected long escalateAfterMillis() {
        return ESCALATE_AFTER_MS;
    }

    @Override
    protected void escalate(Boolean unusedDesired, Observed o) {
        // We've wanted the scan ON for a while but it never engaged (startDiscovery accepted but isDiscovering
        // stays false; the [native LE, meta NONE] desync). Only a reset clears the stuck controller scan.
        if (!scanWantedSupplier.getAsBoolean()) {
            return; // only escalate the can't-START case
        }
        BTAdapter a = adapterSupplier.get();
        if (a != null && resetBudget.tryReset("discovery")) {
            HCIStatusCode rc = a.reset();
            logger.warn("[reconcile:discovery] scan desync; escalation reset -> {} (powered={})", rc, a.isPowered());
            if (rc == HCIStatusCode.SUCCESS && !a.isPowered()) {
                a.setPowered(true);
            }
        }
    }
}
