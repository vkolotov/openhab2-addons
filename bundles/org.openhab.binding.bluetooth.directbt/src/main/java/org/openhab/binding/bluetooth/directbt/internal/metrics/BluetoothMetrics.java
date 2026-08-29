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
package org.openhab.binding.bluetooth.directbt.internal.metrics;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.io.monitor.MeterRegistryProvider;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;

/**
 * Hands out {@link DeviceMetrics} bound to the openHAB core Micrometer registry.
 * <p>
 * The registry is an <b>optional, dynamic</b> reference: {@code org.openhab.core.io.monitor} supplies it, but the
 * binding must keep working when metrics are unavailable, so every caller gets a {@link DeviceMetrics} either way
 * and instrumentation calls are no-ops until a registry shows up. Publishing the metrics for scraping additionally
 * needs the {@code metrics} add-on installed (it owns the {@code /rest/metrics/prometheus} endpoint); the meters
 * themselves are registered regardless.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@Component(service = BluetoothMetrics.class)
@NonNullByDefault
public class BluetoothMetrics {

    /**
     * Fallback used while the core registry is absent. A composite with no delegates accepts every meter and
     * records nothing, which keeps the call sites free of null checks.
     */
    private final CompositeMeterRegistry noop = new CompositeMeterRegistry();

    private volatile @Nullable MeterRegistry registry;

    @Reference(cardinality = ReferenceCardinality.OPTIONAL, policy = ReferencePolicy.DYNAMIC)
    public void setMeterRegistryProvider(MeterRegistryProvider provider) {
        this.registry = provider.getOHMeterRegistry();
    }

    public void unsetMeterRegistryProvider(MeterRegistryProvider provider) {
        this.registry = null;
    }

    /**
     * Creates the metric set for one device. The caller owns it and must {@link DeviceMetrics#close()} it when
     * the device goes away.
     *
     * @param label the device Thing's label, published as the {@code device} tag
     * @param address the Bluetooth address
     * @param adapter the owning adapter's identifier
     */
    public DeviceMetrics forDevice(String label, String address, String adapter) {
        MeterRegistry current = registry;
        return new DeviceMetrics(current != null ? current : noop, label, address, adapter);
    }

    /**
     * Creates the metric set for one adapter — radio arbitration and recovery-ladder activity. The caller owns it
     * and must {@link AdapterMetrics#close()} it when the bridge is disposed.
     *
     * @param adapter the adapter's address, published as the {@code adapter} tag so these join to the per-device
     *            metrics that carry the same tag
     */
    public AdapterMetrics forAdapter(String adapter) {
        MeterRegistry current = registry;
        return new AdapterMetrics(current != null ? current : noop, adapter);
    }
}
