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

import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.direct_bt.BTAdapter;
import org.direct_bt.BTFactory;
import org.direct_bt.BTManager;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.config.discovery.AbstractDiscoveryService;
import org.openhab.core.config.discovery.DiscoveryResultBuilder;
import org.openhab.core.config.discovery.DiscoveryService;
import org.openhab.core.thing.ThingUID;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
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

    @Activate
    public DirectBTDiscoveryService() {
        super(Set.of(DirectBTAdapterConstants.THING_TYPE_DIRECTBT), 10, true);
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
        BTManager manager;
        try {
            DirectBTNativesLoader.extractNatives();
            manager = BTFactory.getDirectBTManager();
        } catch (UnsupportedOperationException e) {
            logger.debug("Direct-BT not supported on this platform: {}", e.getMessage());
            return;
        } catch (Exception e) {
            // Most commonly: missing CAP_NET_ADMIN/CAP_NET_RAW, or bluetoothd owning the adapter.
            logger.debug("Cannot enumerate Direct-BT adapters (caps/bluetoothd?): {}", e.getMessage());
            return;
        }
        var adapters = manager.getAdapters();
        logger.debug("Direct-BT discovery: {} adapter(s) enumerated", adapters.size());
        for (BTAdapter adapter : adapters) {
            String address = adapter.getAddressAndType().address.toString().toUpperCase();
            ThingUID uid = new ThingUID(DirectBTAdapterConstants.THING_TYPE_DIRECTBT, address.replace(":", ""));
            String name = adapter.getName();
            thingDiscovered(DiscoveryResultBuilder.create(uid)
                    .withLabel("Direct-BT Adapter " + (name == null || name.isEmpty() ? address : name))
                    .withProperty(DirectBTAdapterConstants.PROPERTY_ADDRESS, address)
                    .withRepresentationProperty(DirectBTAdapterConstants.PROPERTY_ADDRESS).build());
        }
    }
}
