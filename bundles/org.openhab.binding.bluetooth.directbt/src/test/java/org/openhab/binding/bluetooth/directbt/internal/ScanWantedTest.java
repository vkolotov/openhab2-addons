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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.adapter.AdapterLeaseCoordinator;

/**
 * Regression harness for {@link AdapterLeaseCoordinator#scanWanted} — the pure predicate deciding whether the
 * adapter should be scanning, rolled up from device/config state. Locks down the inbox-discovery-vs-connect
 * precedence: background/manual discovery surfaces new devices to the inbox, but must yield to a configured
 * device that is still trying to establish its connection (a scan restarting between connect attempts starves
 * the create-connection — observed live as a connect/clear-pending flap).
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
class ScanWantedTest {

    // Arguments: (needsDiscovery, backgroundDiscovery, activeScan, connecting, establishing)

    @Test
    void idleWithNothingWantedDoesNotScan() {
        assertFalse(AdapterLeaseCoordinator.scanWanted(false, false, false, false, false));
    }

    @Test
    void configuredDeviceNeedingDiscoveryScans() {
        // A device with no handle needs the scan to be found/connected — this always scans, even mid-establish.
        assertTrue(AdapterLeaseCoordinator.scanWanted(true, false, false, false, false));
        assertTrue(AdapterLeaseCoordinator.scanWanted(true, false, false, false, true),
                "the STATIC fallback still favours discovery; the contention case is intercepted and\n"
                        + "time-sliced by AdapterLeaseCoordinator.decide() before reaching this predicate");
    }

    @Test
    void backgroundDiscoveryScansForTheInboxWhenIdle() {
        assertTrue(AdapterLeaseCoordinator.scanWanted(false, true, false, false, false));
    }

    @Test
    void manualScanScansForTheInboxWhenIdle() {
        assertTrue(AdapterLeaseCoordinator.scanWanted(false, false, true, false, false));
    }

    @Test
    void backgroundDiscoveryYieldsToADeviceEstablishingItsConnection() {
        // THE regression fix: background discovery must not keep the scan on while a configured device is trying
        // to connect (has a handle, not yet connected), or the scan restarts between attempts and the connect
        // never completes.
        assertFalse(AdapterLeaseCoordinator.scanWanted(false, true, false, false, true),
                "background discovery must yield to a device establishing its link");
    }

    @Test
    void manualScanYieldsToADeviceEstablishingItsConnection() {
        assertFalse(AdapterLeaseCoordinator.scanWanted(false, false, true, false, true),
                "a manual inbox scan must also yield to a device establishing its link");
    }

    @Test
    void anyScanIsSuppressedWhileADeviceIsActivelyConnecting() {
        // The single-radio exclusion: the controller rejects create-connection while scanning, so an in-flight
        // connect suppresses the scan even for a device that needs discovery.
        assertFalse(AdapterLeaseCoordinator.scanWanted(true, true, true, true, true));
    }
}
