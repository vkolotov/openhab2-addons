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

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.adapter.AdapterLeaseListener;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;

/**
 * Adapter-scoped metrics: how the single radio is shared, and how often the evidence-gated recovery ladder fires.
 * <p>
 * These are the questions no per-device metric can answer. A device's metrics can show that it waited for a
 * connect lease; only the adapter knows <em>why</em> — that another device held the radio, how the contention
 * time-slice divided it, and whether recovery escalated. Together with
 * {@code openhab.bluetooth.state.seconds.total{waiting_on="CONNECT_LEASE"}} they turn "this device is slow to
 * connect" into either "the radio is oversubscribed" or "this peer is unresponsive".
 * <p>
 * Implemented as an {@link AdapterLeaseListener} so it plugs into the coordinator without touching arbitration.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
public class AdapterMetrics implements AdapterLeaseListener, AutoCloseable {

    private static final String PREFIX = "openhab.bluetooth.adapter.";

    /** Wall time the radio spent scanning vs connecting — the radio's duty split. */
    private static final String RADIO_SECONDS = PREFIX + "radio.seconds.total";
    /** Ticks where discovery and establishment both wanted the radio. */
    private static final String CONTENTION_TICKS = PREFIX + "contention.ticks.total";
    /** Time each contention slice actually held the radio, by owner. */
    private static final String SLICE_SECONDS = PREFIX + "slice.seconds.total";
    private static final String SLICE_SWITCHES = PREFIX + "slice.switches.total";
    /** Recovery-ladder activity: armed, cleared, escalated, suppressed. */
    private static final String RECOVERY = PREFIX + "recovery.total";
    /** Current ladder rung: 0 = idle, 1 = evidence armed, 2 = cleanup done, 3 = reset issued. */
    private static final String LADDER_RUNG = PREFIX + "recovery.rung";
    /**
     * Controller liveness: 1 healthy, 0 after an unrecoverable hardware fault. Distinct from the adapter looking
     * valid/powered, which stays true through such a fault.
     */
    private static final String CONTROLLER_HEALTHY = PREFIX + "controller.healthy";
    /** Seconds the controller has been continuously faulted, 0 while healthy. The "is it stuck?" signal. */
    private static final String CONTROLLER_UNHEALTHY_SECONDS = PREFIX + "controller.unhealthy.seconds";
    /** HCI HARDWARE_ERROR events observed. */
    private static final String CONTROLLER_ERRORS = PREFIX + "controller.errors.total";
    /** Forced resets issued in response to a controller fault, by outcome. */
    private static final String FORCED_RESETS = PREFIX + "controller.forced.resets.total";
    /**
     * Devices retained in Direct-BT's native discovered list. Unbounded without periodic discovery restarts, so
     * this is the signal that the restart is actually flushing.
     */
    private static final String DISCOVERED_DEVICES = PREFIX + "discovered.devices";
    /** Discovery restarts issued to flush the discovered list. */
    private static final String DISCOVERY_RESTARTS = PREFIX + "discovery.restarts.total";

    private final MeterRegistry registry;
    private final Tags baseTags;
    private final AtomicInteger ladderRung = new AtomicInteger();
    private final AtomicInteger controllerHealthy = new AtomicInteger(1);
    private final AtomicInteger discoveredDevices = new AtomicInteger();
    /** Monotonic ms when the controller was first seen faulted, 0 while healthy. */
    private final AtomicLong controllerUnhealthySince = new AtomicLong();

    public AdapterMetrics(MeterRegistry registry, String adapter) {
        this.registry = registry;
        this.baseTags = Tags.of("adapter", adapter);

        Gauge.builder(LADDER_RUNG, ladderRung, AtomicInteger::get)
                .description("Recovery ladder rung: 0 idle, 1 evidence armed, 2 cleanup done, 3 reset issued")
                .tags(baseTags).strongReference(true).register(registry);
        Gauge.builder(CONTROLLER_HEALTHY, controllerHealthy, AtomicInteger::get)
                .description("Controller liveness: 1 healthy, 0 hardware fault").tags(baseTags).strongReference(true)
                .register(registry);
        Gauge.builder(CONTROLLER_UNHEALTHY_SECONDS, controllerUnhealthySince,
                since -> since.get() == 0 ? 0.0 : (System.nanoTime() / 1_000_000L - since.get()) / 1000.0)
                .description("Seconds the controller has been continuously faulted, 0 while healthy").tags(baseTags)
                .strongReference(true).register(registry);
        // Pre-register so a "did a forced reset ever run?" query returns 0 rather than no series at all.
        for (String outcome : List.of("success", "failure")) {
            Counter.builder(FORCED_RESETS).description("Forced resets issued for a controller fault")
                    .tags(baseTags.and("outcome", outcome)).register(registry);
        }
        Counter.builder(CONTROLLER_ERRORS).description("HCI HARDWARE_ERROR events observed").tags(baseTags)
                .register(registry);
        Gauge.builder(DISCOVERED_DEVICES, discoveredDevices, AtomicInteger::get)
                .description("Devices retained in the native discovered list").tags(baseTags).strongReference(true)
                .register(registry);
        Counter.builder(DISCOVERY_RESTARTS).description("Discovery restarts issued to flush the discovered list")
                .tags(baseTags).register(registry);
        // Pre-register both radio modes so the duty split is a complete ratio from the first scrape.
        for (String mode : List.of("scanning", "connecting")) {
            Counter.builder(RADIO_SECONDS).description("Radio time by mode").tags(baseTags.and("mode", mode))
                    .register(registry);
        }
    }

    @Override
    public void onScanDecision(boolean scanning, boolean contended) {
        // Each decision covers one reconcile tick (~2s). Counting ticks rather than seconds keeps this free of a
        // clock dependency; the ratio between modes is what matters, and both are sampled identically.
        registry.counter(RADIO_SECONDS, baseTags.and("mode", scanning ? "scanning" : "connecting")).increment();
        if (contended) {
            registry.counter(CONTENTION_TICKS, baseTags).increment();
        }
    }

    @Override
    public void onSliceSwitched(boolean discoverySlice, long previousSliceMs) {
        // The OUTGOING slice is the one whose time just completed.
        String owner = discoverySlice ? "connect" : "discovery";
        registry.counter(SLICE_SECONDS, baseTags.and("owner", owner)).increment(previousSliceMs / 1000.0);
        registry.counter(SLICE_SWITCHES, baseTags).increment();
    }

    @Override
    public void onRecoveryEvidenceArmed(String deviceId) {
        registry.counter(RECOVERY, baseTags.and("event", "armed")).increment();
        ladderRung.set(1);
    }

    @Override
    public void onRecoveryEvidenceCleared(String deviceId, String reason, long evidenceAgeMs) {
        // The healthy outcome: evidence arose and resolved without touching the radio.
        registry.counter(RECOVERY, baseTags.and("event", "cleared")).increment();
        ladderRung.set(0);
    }

    @Override
    public void onRecoveryEscalated(int rung, String deviceId, long evidenceAgeMs) {
        registry.counter(RECOVERY, baseTags.and("event", rung >= 2 ? "reset" : "cleanup")).increment();
        ladderRung.set(rung >= 2 ? 3 : 2);
    }

    /**
     * Record the controller's liveness, as observed on the reconcile tick. Idempotent: the unhealthy-since clock
     * starts on the first faulted observation and is only cleared by an observed recovery, so the
     * {@code unhealthy.seconds} gauge measures one continuous outage rather than restarting every tick.
     */
    public void onControllerHealth(boolean healthy) {
        controllerHealthy.set(healthy ? 1 : 0);
        if (healthy) {
            controllerUnhealthySince.set(0);
        } else {
            controllerUnhealthySince.compareAndSet(0, System.nanoTime() / 1_000_000L);
        }
    }

    /** Record an observed HCI HARDWARE_ERROR. */
    public void onControllerHardwareError() {
        registry.counter(CONTROLLER_ERRORS, baseTags).increment();
        onControllerHealth(false);
    }

    /** Record the size of the native discovered-device list, as sampled before a flush. */
    public void onDiscoveredDeviceCount(int count) {
        discoveredDevices.set(count);
    }

    /** Record a discovery restart issued to flush the discovered list. */
    public void onDiscoveryRestart() {
        registry.counter(DISCOVERY_RESTARTS, baseTags).increment();
    }

    /** Record a forced reset issued in response to a controller fault. */
    public void onForcedReset(boolean success) {
        registry.counter(FORCED_RESETS, baseTags.and("outcome", success ? "success" : "failure")).increment();
    }

    @Override
    public void onResetSuppressed(String deviceId) {
        // Suppressions prove the evidence gate is working: one unreachable peripheral must never reset healthy ones.
        registry.counter(RECOVERY, baseTags.and("event", "suppressed")).increment();
    }

    @Override
    public void close() {
        ladderRung.set(0);
        registry.getMeters().stream().map(Meter::getId)
                .filter(id -> id.getName().startsWith(PREFIX) && id.getTags().containsAll(baseTags.stream().toList()))
                .forEach(registry::remove);
    }
}
