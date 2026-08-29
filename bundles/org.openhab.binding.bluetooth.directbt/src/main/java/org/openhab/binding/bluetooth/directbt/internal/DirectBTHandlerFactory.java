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

import java.util.HashMap;
import java.util.Hashtable;
import java.util.Map;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.bluetooth.BluetoothAdapter;
import org.openhab.binding.bluetooth.directbt.internal.metrics.BluetoothMetrics;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.UID;
import org.openhab.core.thing.binding.BaseThingHandlerFactory;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.thing.binding.ThingHandlerFactory;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates Direct-BT adapter bridge handlers.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
@Component(service = ThingHandlerFactory.class, configurationPid = "binding.bluetooth.directbt")
public class DirectBTHandlerFactory extends BaseThingHandlerFactory {

    private static final Set<ThingTypeUID> SUPPORTED_THING_TYPES_UIDS = Set
            .of(DirectBTAdapterConstants.THING_TYPE_DIRECTBT);

    private final Logger logger = LoggerFactory.getLogger(DirectBTHandlerFactory.class);

    private final Map<ThingUID, ServiceRegistration<?>> serviceRegs = new HashMap<>();

    private final DirectBTManagerFactory managerFactory;

    private final BluetoothMetrics metrics;

    @Activate
    public DirectBTHandlerFactory(@Reference DirectBTManagerFactory managerFactory,
            @Reference BluetoothMetrics metrics) {
        this.managerFactory = managerFactory;
        this.metrics = metrics;
    }

    @Override
    public boolean supportsThingType(ThingTypeUID thingTypeUID) {
        return SUPPORTED_THING_TYPES_UIDS.contains(thingTypeUID);
    }

    @Override
    protected @Nullable ThingHandler createHandler(Thing thing) {
        logger.debug("Creating Direct-BT handler for {} with config {}", thing.getUID(), thing.getConfiguration());
        if (thing.getThingTypeUID().equals(DirectBTAdapterConstants.THING_TYPE_DIRECTBT)) {
            DirectBTBridgeHandler handler = new DirectBTBridgeHandler((Bridge) thing, managerFactory, metrics);
            registerBluetoothAdapter(handler);
            return handler;
        }
        return null;
    }

    private synchronized void registerBluetoothAdapter(BluetoothAdapter adapter) {
        this.serviceRegs.put(adapter.getUID(),
                bundleContext.registerService(BluetoothAdapter.class.getName(), adapter, new Hashtable<>()));
    }

    @Override
    protected synchronized void removeHandler(ThingHandler thingHandler) {
        if (thingHandler instanceof BluetoothAdapter bluetoothAdapter) {
            UID uid = bluetoothAdapter.getUID();
            ServiceRegistration<?> serviceReg = this.serviceRegs.remove(uid);
            if (serviceReg != null) {
                serviceReg.unregister();
            }
        }
    }
}
