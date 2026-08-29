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

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openhab.binding.bluetooth.directbt.internal.metrics.DeviceMetrics.Cause;
import org.openhab.binding.bluetooth.directbt.internal.metrics.DeviceMetrics.Op;
import org.openhab.binding.bluetooth.directbt.internal.metrics.DeviceMetrics.Phase;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

/**
 * Locks down the metric contract the observability dashboards are built on: the meter names/tags the queries
 * reference, the availability gauge being time-sampled rather than attempt-derived, and meter cleanup on close.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
class DeviceMetricsTest {

    private static final String DEVICE = "Water Tank";
    private static final String ADDRESS = "70:B9:50:92:A9:90";
    private static final String ADAPTER = "00:01:95:4B:42:BC";

    private final MeterRegistry registry = new SimpleMeterRegistry();

    private @NonNullByDefault({}) DeviceMetrics metrics;

    private final Tags baseTags = Tags.of("device", DEVICE, "address", ADDRESS, "adapter", ADAPTER);

    @BeforeEach
    void setUp() {
        metrics = new DeviceMetrics(registry, DEVICE, ADDRESS, ADAPTER);
    }

    @Test
    void meterSeriesExistBeforeAnyTrafficSoAQuietDeviceIsNotIndistinguishableFromAMissingOne() {
        Gauge up = registry.find("openhab.bluetooth.device.up").tags(baseTags).gauge();
        assertNotNull(up, "the availability gauge must exist from construction");
        assertEquals(0.0, up.value(), "a device starts unavailable until proven otherwise");

        for (Op op : Op.values()) {
            assertNotNull(registry.find("openhab.bluetooth.op.total").tags(baseTags.and("op", tag(op))).counter(),
                    "every operation class must report a zero series before its first operation");
        }
    }

    @Test
    void availabilityIsDrivenByTheGaugeNotByOperationOutcomes() {
        // The point of the gauge: it reflects the CURRENT state, so a scraper sampling it on a fixed cadence
        // yields time-weighted availability. Operations do not move it.
        metrics.setUp(true);
        assertEquals(1.0, gaugeValue());

        metrics.operation(Op.READ);
        metrics.failure(Op.READ, Cause.EMPTY_READ);
        assertEquals(1.0, gaugeValue(), "a failed read does not by itself make the device unavailable");

        metrics.setUp(false);
        assertEquals(0.0, gaugeValue());
    }

    @Test
    void reliabilityIsDerivableAsOperationsMinusErrors() {
        for (int i = 0; i < 10; i++) {
            metrics.operation(Op.READ);
        }
        metrics.failure(Op.READ, Cause.EMPTY_READ);
        metrics.failure(Op.READ, Cause.DISCONNECTED);

        double total = registry.get("openhab.bluetooth.op.total").tags(baseTags.and("op", "read")).counter().count();
        double errors = registry.find("openhab.bluetooth.op.errors.total").tags(baseTags.and("op", "read")).counters()
                .stream().mapToDouble(c -> c.count()).sum();

        assertEquals(10.0, total);
        assertEquals(2.0, errors);
        assertEquals(0.8, (total - errors) / total, 1e-9, "reliability = (total - errors) / total");
    }

    @Test
    void failuresAreSplitByCauseSoADashboardCanTellStackFaultsFromDeviceFaults() {
        metrics.failure(Op.READ, Cause.EMPTY_READ);
        metrics.failure(Op.READ, Cause.EMPTY_READ);
        metrics.failure(Op.READ, Cause.DISCONNECTED);

        assertEquals(2.0, registry.get("openhab.bluetooth.op.errors.total")
                .tags(baseTags.and("op", "read").and("cause", "empty_read")).counter().count());
        assertEquals(1.0, registry.get("openhab.bluetooth.op.errors.total")
                .tags(baseTags.and("op", "read").and("cause", "disconnected")).counter().count());
    }

    @Test
    void connectPhasesAreTimedSeparately() {
        // Establishing the link and resolving GATT have different latencies and different failure modes, so
        // they must not collapse into one series.
        metrics.record(Op.CONNECT, Phase.LINK, TimeUnit.MILLISECONDS.toNanos(400));
        metrics.record(Op.CONNECT, Phase.GATT, TimeUnit.MILLISECONDS.toNanos(1500));

        assertEquals(1, registry.get("openhab.bluetooth.op.duration").tags(baseTags.and("op", "connect"))
                .tag("phase", "link").timer().count());
        assertEquals(1500.0, registry.get("openhab.bluetooth.op.duration").tags(baseTags.and("op", "connect"))
                .tag("phase", "gatt").timer().totalTime(TimeUnit.MILLISECONDS), 1.0);
    }

    @Test
    void latencyIsRecordedAsATimerPerOperationClass() {
        metrics.record(Op.READ, Phase.NONE, TimeUnit.MILLISECONDS.toNanos(25));

        Timer timer = registry.find("openhab.bluetooth.op.duration").tags(baseTags.and("op", "read")).timer();
        assertNotNull(timer);
        assertEquals(1, timer.count());
        assertEquals(25.0, timer.totalTime(TimeUnit.MILLISECONDS), 1.0);
    }

    @Test
    void latencyPublishesHistogramBucketsSoPercentilesStayAggregatableOverAnyWindow() {
        // Asserted against a Prometheus registry on purpose: SimpleMeterRegistry does not materialise buckets in
        // its snapshots, so only the real backend proves the "p95 over an arbitrary window" contract. Without
        // _bucket{le=...} series, histogram_quantile() cannot be computed and every latency panel breaks.
        PrometheusMeterRegistry prometheus = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        DeviceMetrics scraped = new DeviceMetrics(prometheus, DEVICE, ADDRESS, ADAPTER);

        scraped.record(Op.READ, Phase.NONE, TimeUnit.MILLISECONDS.toNanos(25));

        assertTrue(
                prometheus.scrape().lines()
                        .anyMatch(line -> line.startsWith("openhab_bluetooth_op_duration_seconds_bucket")
                                && line.contains("le=")),
                "histogram buckets are required for histogram_quantile() over a range");
    }

    @Test
    void closeRemovesEveryMeterSoADeletedDeviceStopsPublishingAStaleSeries() {
        metrics.setUp(true);
        metrics.operation(Op.READ);
        metrics.failure(Op.READ, Cause.EMPTY_READ);
        metrics.record(Op.WRITE, Phase.NONE, 1000);
        metrics.connectAttempted();
        assertFalse(registry.getMeters().isEmpty());

        metrics.close();

        assertTrue(registry.getMeters().stream().noneMatch(m -> m.getId().getName().startsWith("openhab.bluetooth.")),
                "a disposed device must leave no meters behind");
    }

    @Test
    void closeLeavesOtherDevicesMetersIntact() {
        DeviceMetrics other = new DeviceMetrics(registry, "Septic Sensor", "FE:0F:C0:71:5E:01", ADAPTER);
        other.setUp(true);
        metrics.setUp(true);

        metrics.close();

        assertNotNull(registry.find("openhab.bluetooth.device.up").tag("device", "Septic Sensor").gauge(),
                "closing one device must not remove another device's meters");
        assertNull(registry.find("openhab.bluetooth.device.up").tag("device", DEVICE).gauge());
    }

    @Test
    void theNoopSinkAcceptsEveryCallAndRegistersNothing() {
        // Devices without a configured Thing route here: passing phones/beacons advertise with rotating random
        // addresses, so instrumenting them would grow the series count without bound.
        DeviceMetrics.NOOP.setUp(true);
        DeviceMetrics.NOOP.connectAttempted();
        DeviceMetrics.NOOP.operation(Op.READ);
        DeviceMetrics.NOOP.failure(Op.READ, Cause.EMPTY_READ);
        DeviceMetrics.NOOP.record(Op.READ, Phase.NONE, 1000);
        DeviceMetrics.NOOP.close();

        assertTrue(registry.getMeters().stream().noneMatch(m -> m.getId().getTags().contains(Tag.of("device", ""))),
                "the no-op sink must not publish into the shared registry");
    }

    @Test
    void connectsAndDisconnectsAreCountedSeparatelyFromTheMultiPurposeOpCounter() {
        // op.total{op="connect"} is incremented from several lifecycle points, so it cannot answer "how many
        // times did this device connect". These two counters can, which is why they exist separately.
        metrics.connected();
        metrics.connected();
        metrics.disconnected();

        assertEquals(2.0, registry.get("openhab.bluetooth.connects.total").tags(baseTags).counter().count());
        assertEquals(1.0, registry.get("openhab.bluetooth.disconnects.total").tags(baseTags).counter().count());
        assertEquals(0.0,
                registry.get("openhab.bluetooth.op.total").tags(baseTags.and("op", "connect")).counter().count(),
                "establishing a link must not touch the operation counter");
    }

    private double gaugeValue() {
        Gauge gauge = registry.find("openhab.bluetooth.device.up").tags(baseTags).gauge();
        assertNotNull(gauge);
        return gauge.value();
    }

    private static String tag(Op op) {
        return op.name().toLowerCase();
    }
}
