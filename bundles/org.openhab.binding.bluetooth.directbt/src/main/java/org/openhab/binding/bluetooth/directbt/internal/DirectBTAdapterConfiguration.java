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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.bluetooth.BaseBluetoothBridgeHandlerConfiguration;

/**
 * Configuration for a Direct-BT adapter bridge.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
public class DirectBTAdapterConfiguration extends BaseBluetoothBridgeHandlerConfiguration {

    public @Nullable String address;

    /**
     * LE scan interval/window in 0.625 ms slots. The window/interval ratio is the scan duty cycle:
     * the radio listens for {@code scanWindowSlots} out of every {@code scanIntervalSlots}. The
     * conservative default (24/144 = ~17%) keeps established connections alive (a near-100% duty
     * scan starves connected devices' ACL slots and drops them on some controllers), at the
     * cost of slower discovery of weak/far advertisers.
     */
    public int scanIntervalSlots = 144;
    public int scanWindowSlots = 24;

    /**
     * LE connection interval in 1.25 ms slots. Direct-BT pins min=max to this value because testing showed
     * a loose 30-50 ms range lets some controllers choose 50 ms, which caused peripheral-side GATT stalls.
     */
    public int connectionIntervalSlots = 24;

    /**
     * LE supervision timeout in 10 ms slots. A longer timeout tolerates short RF fades on long-range links,
     * at the cost of slower detection when a peer is truly gone.
     */
    public int connectionSupervisionTimeoutSlots = 600;

    /**
     * Controller-side duplicate advert filtering. OFF (default) reports every advert: activity stamps stay
     * fresh and a device can be picked up whenever its Thing becomes ready. ON reports each static-address
     * device ONCE per scan session — a Thing that wasn't ready at that moment never sees it again.
     */
    public boolean scanDuplicateFilter = false;

    /**
     * Minutes between discovery restarts, or 0 to never restart. Direct-BT keeps every discovered address in its
     * native discovered-device list, and a device rejected by all listeners stays there so it can still be adopted
     * when a connection completes. That list is only flushed by {@code startDiscovery()}, which the reconciler
     * otherwise calls once and leaves running, so with rotating private addresses the list grows for as long as the
     * process lives (about 1,270 retained devices over 13 hours in one measurement, each holding three EIR objects).
     * Restarting discovery periodically flushes the unshared entries; connected devices are also in the shared list
     * and survive. The restart is skipped while any device is connecting or resolving its GATT model.
     */
    public int discoveryRestartMinutes = 30;
}
