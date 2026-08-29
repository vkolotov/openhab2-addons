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

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.direct_bt.BTAdapter;
import org.direct_bt.BTManager;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.config.discovery.AbstractDiscoveryService;
import org.openhab.core.config.discovery.DiscoveryResultBuilder;
import org.openhab.core.config.discovery.DiscoveryService;
import org.openhab.core.thing.ThingUID;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Discovers the Direct-BT controllable Bluetooth adapters present on this host and offers them as
 * {@code directbt} bridge things in the inbox.
 * <p>
 * NOTE: unlike the BlueZ discovery (which enumerates adapters over the unprivileged bluetoothd D-Bus
 * API), enumerating adapters here opens the privileged HCI management socket, so this requires the
 * openHAB process to hold {@code CAP_NET_ADMIN}/{@code CAP_NET_RAW} (see README). Without those caps
 * the manager cannot be obtained and no adapters are discovered. Also note the same physical adapter
 * may appear in the inbox under both the BlueZ and Direct-BT transports — only one can own it at a time.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
@Component(service = DiscoveryService.class, configurationPid = "discovery.bluetooth.directbt")
public class DirectBTDiscoveryService extends AbstractDiscoveryService {

    private final Logger logger = LoggerFactory.getLogger(DirectBTDiscoveryService.class);

    private @Nullable Future<?> backgroundScan;

    private final DirectBTManagerFactory managerFactory;

    @Activate
    public DirectBTDiscoveryService(@Reference DirectBTManagerFactory managerFactory) {
        super(Set.of(DirectBTAdapterConstants.THING_TYPE_DIRECTBT), 10, true);
        this.managerFactory = managerFactory;
    }

    @Override
    protected void startBackgroundDiscovery() {
        backgroundScan = scheduler.scheduleWithFixedDelay(this::startScan, 5, 60, TimeUnit.SECONDS);
    }

    @Override
    protected void stopBackgroundDiscovery() {
        Future<?> scan = backgroundScan;
        if (scan != null) {
            scan.cancel(false);
            backgroundScan = null;
        }
    }

    @Override
    protected void startScan() {
        // The manager (and its native libraries) is owned by the long-lived DirectBTManagerFactory; we only
        // query it. It may not be ready yet (native loading / missing caps / bluetoothd owns the adapter).
        BTManager manager = managerFactory.getManager();
        if (manager == null) {
            logger.debug("Direct-BT manager not ready; skipping adapter discovery scan");
            return;
        }
        var adapters = manager.getAdapters();
        logger.debug("Direct-BT discovery: {} adapter(s) enumerated", adapters.size());
        for (BTAdapter adapter : adapters) {
            String address = adapter.getAddressAndType().address.toString().toUpperCase(Locale.ROOT);
            // Bridge id is the adapter MAC (stable across reboot, unique per adapter), so device UIDs read
            // bluetooth:generic:<adapterMAC>:<deviceMAC> — fully qualified by which adapter they connect through.
            ThingUID uid = new ThingUID(DirectBTAdapterConstants.THING_TYPE_DIRECTBT, address.replace(":", ""));
            String name = sanitizeName(adapter.getName());
            thingDiscovered(DiscoveryResultBuilder.create(uid)
                    .withLabel("Direct-BT Adapter " + (name.isEmpty() ? address : name))
                    .withProperty(DirectBTAdapterConstants.PROPERTY_ADDRESS, address)
                    .withRepresentationProperty(DirectBTAdapterConstants.PROPERTY_ADDRESS).build());
        }
    }

    /**
     * The controller local name comes from a fixed 248-byte HCI field that should be NUL-padded, but some
     * adapters ship it padded with junk (e.g. the TP-Link UB500 emits {@code "TP-Link UB500 Adapter"}
     * followed by a run of 0xFF bytes, which decode to {@code ÿÿÿ…} — U+00FF). NUL termination is already
     * handled upstream; strip only a <em>trailing</em> run of 0xFF/control padding so legitimate interior
     * non-ASCII (accented UTF-8 names) is preserved.
     */
    static String sanitizeName(@Nullable String name) {
        if (name == null) {
            return "";
        }
        int end = name.length();
        while (end > 0) {
            char c = name.charAt(end - 1);
            if (c == 0x00ff || c < 0x20) {
                end--;
            } else {
                break;
            }
        }
        return name.substring(0, end).trim();
    }
}
