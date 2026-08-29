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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Locks down the adapter metric contract: the radio duty split, contention slice fairness, and the
 * evidence-gated recovery ladder — the things no per-device metric can show.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
class AdapterMetricsTest {

    private static final String ADAPTER = "AA:BB:CC:DD:EE:FF";

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final Tags baseTags = Tags.of("adapter", ADAPTER);

    private @NonNullByDefault({}) AdapterMetrics metrics;

    @BeforeEach
    void setUp() {
        metrics = new AdapterMetrics(registry, ADAPTER);
    }

    @Test
    void radioDutySplitIsACompleteRatioFromTheFirstScrape() {
        // Both modes are pre-registered, so "scanning / (scanning + connecting)" is computable before the radio
        // has ever been in one of them. Otherwise an absent series and a zero look identical.
        assertNotNull(registry.find("openhab.bluetooth.adapter.radio.seconds.total")
                .tags(baseTags.and("mode", "scanning")).counter());
        assertNotNull(registry.find("openhab.bluetooth.adapter.radio.seconds.total")
                .tags(baseTags.and("mode", "connecting")).counter());
    }

    @Test
    void radioTimeIsSplitByMode() {
        metrics.onScanDecision(true, false);
        metrics.onScanDecision(true, false);
        metrics.onScanDecision(false, false);

        assertEquals(2.0, registry.get("openhab.bluetooth.adapter.radio.seconds.total")
                .tags(baseTags.and("mode", "scanning")).counter().count());
        assertEquals(1.0, registry.get("openhab.bluetooth.adapter.radio.seconds.total")
                .tags(baseTags.and("mode", "connecting")).counter().count());
    }

    @Test
    void contentionIsCountedOnlyWhenBothDemandsCompete() {
        metrics.onScanDecision(true, true);
        metrics.onScanDecision(false, true);
        metrics.onScanDecision(true, false);

        assertEquals(2.0,
                registry.get("openhab.bluetooth.adapter.contention.ticks.total").tags(baseTags).counter().count());
    }

    @Test
    void sliceTimeIsAttributedToTheOutgoingOwner() {
        // Switching TO discovery means the CONNECT slice just finished, so its time belongs to connect. Getting
        // this backwards would invert the fairness picture entirely.
        metrics.onSliceSwitched(true, 16_000);
        metrics.onSliceSwitched(false, 30_000);

        assertEquals(16.0, registry.get("openhab.bluetooth.adapter.slice.seconds.total")
                .tags(baseTags.and("owner", "connect")).counter().count(), 0.001);
        assertEquals(30.0, registry.get("openhab.bluetooth.adapter.slice.seconds.total")
                .tags(baseTags.and("owner", "discovery")).counter().count(), 0.001);
        assertEquals(2.0,
                registry.get("openhab.bluetooth.adapter.slice.switches.total").tags(baseTags).counter().count());
    }

    @Test
    void theRecoveryLadderIsTrackedRungByRung() {
        assertEquals(0.0, rung(), "idle until evidence arms");

        metrics.onRecoveryEvidenceArmed("11:22:33:44:55:88");
        assertEquals(1.0, rung());

        metrics.onRecoveryEscalated(1, "11:22:33:44:55:88", 180_000);
        assertEquals(2.0, rung(), "targeted cleanup performed");

        metrics.onRecoveryEscalated(2, "11:22:33:44:55:88", 360_000);
        assertEquals(3.0, rung(), "adapter reset issued");

        assertEquals(1.0, recovery("armed"));
        assertEquals(1.0, recovery("cleanup"));
        assertEquals(1.0, recovery("reset"));
    }

    @Test
    void evidenceClearingReturnsTheLadderToIdle() {
        // The healthy outcome: evidence arose and resolved without ever touching the radio.
        metrics.onRecoveryEvidenceArmed("11:22:33:44:55:88");
        metrics.onRecoveryEvidenceCleared("11:22:33:44:55:88", "target advertised again", 4_000);

        assertEquals(0.0, rung());
        assertEquals(1.0, recovery("cleared"));
    }

    @Test
    void suppressedResetsAreCountedSoTheEvidenceGateIsVisible() {
        // A suppression is the gate WORKING: one unreachable peripheral must never reset healthy peers. Counting
        // it separately distinguishes "the gate held" from "the gate never armed".
        metrics.onRecoveryEvidenceArmed("11:22:33:44:55:88");
        metrics.onResetSuppressed("11:22:33:44:55:88");

        assertEquals(1.0, recovery("suppressed"));
        assertEquals(0.0, recovery("reset"), "no reset was issued");
    }

    @Test
    void closeRemovesEveryAdapterMeter() {
        metrics.onScanDecision(true, true);
        metrics.onSliceSwitched(true, 16_000);
        metrics.onRecoveryEvidenceArmed("11:22:33:44:55:88");

        metrics.close();

        assertTrue(
                registry.getMeters().stream()
                        .noneMatch(m -> m.getId().getName().startsWith("openhab.bluetooth.adapter.")),
                "a disposed bridge must leave no adapter meters behind");
    }

    // ---------------------------------------------------------------------------------------------
    // Controller health. These are the signals that answer "is the adapter wedged, and did recovery run?" —
    // the question the 16 Aug outage could not be answered from, because nothing exposed controller liveness.
    // ---------------------------------------------------------------------------------------------
    @Test
    void controllerHealthGaugeTracksFaultAndRecovery() {
        assertEquals(1.0, controllerHealthy(), "healthy until told otherwise");

        metrics.onControllerHardwareError();
        assertEquals(0.0, controllerHealthy(), "a hardware error marks the controller unhealthy");
        assertEquals(1.0, controllerErrors(), "the error is counted");

        metrics.onControllerHealth(true);
        assertEquals(1.0, controllerHealthy(), "recovery restores the gauge");
        assertEquals(0.0, unhealthySeconds(), "the outage clock resets once healthy");
    }

    @Test
    void unhealthyDurationMeasuresOneContinuousOutage() throws InterruptedException {
        metrics.onControllerHealth(false);
        Thread.sleep(15);
        // Re-observing the same fault on a later tick must NOT restart the clock, else a persistent outage would
        // always read as a few seconds old and "how long has this been stuck?" would be unanswerable.
        metrics.onControllerHealth(false);

        assertTrue(unhealthySeconds() > 0.0, "a continuing fault keeps ageing rather than resetting each tick");
    }

    @Test
    void forcedResetOutcomesAreCountedSeparately() {
        // Pre-registered, so "did a forced reset ever run?" reads 0 rather than returning no series at all.
        assertEquals(0.0, forcedResets("success"));
        assertEquals(0.0, forcedResets("failure"));

        metrics.onForcedReset(true);
        metrics.onForcedReset(false);

        assertEquals(1.0, forcedResets("success"));
        assertEquals(1.0, forcedResets("failure"));
    }

    @Test
    void discoveredDeviceCountAndRestartsAreTracked() {
        // Pre-registered so a "is the discovered list growing?" query returns a series from the first scrape.
        assertEquals(0.0, discoveredDevices());
        assertEquals(0.0, discoveryRestarts());

        metrics.onDiscoveredDeviceCount(1267);
        metrics.onDiscoveryRestart();

        assertEquals(1267.0, discoveredDevices(), "the pre-flush retained count is what proves the flush works");
        assertEquals(1.0, discoveryRestarts());
    }

    private double discoveredDevices() {
        return registry.get("openhab.bluetooth.adapter.discovered.devices").tags(baseTags).gauge().value();
    }

    private double discoveryRestarts() {
        return registry.get("openhab.bluetooth.adapter.discovery.restarts.total").tags(baseTags).counter().count();
    }

    private double controllerHealthy() {
        return registry.get("openhab.bluetooth.adapter.controller.healthy").tags(baseTags).gauge().value();
    }

    private double unhealthySeconds() {
        return registry.get("openhab.bluetooth.adapter.controller.unhealthy.seconds").tags(baseTags).gauge().value();
    }

    private double controllerErrors() {
        return registry.get("openhab.bluetooth.adapter.controller.errors.total").tags(baseTags).counter().count();
    }

    private double forcedResets(String outcome) {
        return registry.get("openhab.bluetooth.adapter.controller.forced.resets.total")
                .tags(baseTags.and("outcome", outcome)).counter().count();
    }

    private double rung() {
        return registry.get("openhab.bluetooth.adapter.recovery.rung").tags(baseTags).gauge().value();
    }

    private double recovery(String event) {
        return registry.find("openhab.bluetooth.adapter.recovery.total").tags(baseTags.and("event", event))
                .counter() == null ? 0.0
                        : registry.get("openhab.bluetooth.adapter.recovery.total").tags(baseTags.and("event", event))
                                .counter().count();
    }
}
