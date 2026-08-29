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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.eclipse.jdt.annotation.NonNullByDefault;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;

/**
 * Per-device service-level metrics for the Direct-BT transport, published into the openHAB core Micrometer
 * registry (see {@code org.openhab.core.io.monitor.MeterRegistryProvider}) and therefore scrapeable from
 * {@code /rest/metrics/prometheus}.
 * <p>
 * Three operation classes are measured separately — {@link Op#CONNECT}, {@link Op#READ} and {@link Op#WRITE} —
 * because they fail in different ways and a single aggregate hides exactly the faults worth finding.
 * <p>
 * <b>Availability and reliability are deliberately different metric shapes.</b>
 * <ul>
 * <li><b>Availability</b> comes from the {@code up} <i>gauge</i>, sampled by the scraper on a fixed cadence, so
 * it is time-weighted ({@code avg_over_time(...[30d])}). A success ratio cannot express availability: while a
 * device is disconnected no operation is attempted, so {@code successes/attempts} would sit at 100% for the whole
 * outage.</li>
 * <li><b>Reliability</b> is the counter ratio {@code ok/total} per operation class, which is meaningful precisely
 * because operations are attempted often.</li>
 * </ul>
 * <p>
 * Latency uses {@link Timer} with a <b>percentile histogram</b> rather than pre-computed percentiles: client-side
 * percentiles are already aggregated and cannot be re-aggregated over an arbitrary range, so a "p95 over 30 days"
 * is only answerable from histogram buckets.
 * <p>
 * All meters are registered eagerly in the constructor so a device that never succeeds still reports zeros
 * (an absent series and a failing device look identical in a query otherwise), and are removed again by
 * {@link #close()} so a deleted Thing does not leak a time series for the lifetime of the runtime.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
public class DeviceMetrics implements AutoCloseable {

    /** Metric name prefix. Dot-separated; Micrometer maps this onto each backend's naming convention. */
    private static final String PREFIX = "openhab.bluetooth.";

    private static final String UP = PREFIX + "device.up";
    private static final String OPS = PREFIX + "op.total";
    private static final String ERRORS = PREFIX + "op.errors.total";
    private static final String DURATION = PREFIX + "op.duration";
    private static final String CONNECT_ATTEMPTS = PREFIX + "connect.attempts.total";
    /**
     * Links actually established — incremented once, where the controller reports the link up. Deliberately
     * separate from {@link #OPS}: that counter is incremented from several different lifecycle points for
     * {@code op="connect"}, so it cannot answer "how many times did this device connect".
     */
    private static final String CONNECTS = PREFIX + "connects.total";
    /** Links lost after having been established, by cause — i.e. how many times a reconnect was needed. */
    private static final String DISCONNECTS = PREFIX + "disconnects.total";

    // --- control-plane (FSM) metrics -------------------------------------------------------------------
    /** Time budget of the control plane: seconds spent in each actor state, split by what it waited on. */
    private static final String STATE_SECONDS = PREFIX + "state.seconds.total";
    /** How often each state is entered — with STATE_SECONDS this separates "slow once" from "fast but often". */
    private static final String TRANSITIONS = PREFIX + "transitions.total";
    private static final String PROCEDURE_STARTS = PREFIX + "procedure.starts.total";
    private static final String PROCEDURE_OUTCOMES = PREFIX + "procedure.total";
    private static final String PROCEDURE_DURATION = PREFIX + "procedure.duration";
    /** Control-plane restarts. The headline "are we thrashing?" signal. */
    private static final String GENERATIONS = PREFIX + "generation.total";
    private static final String DEADLINES = PREFIX + "deadline.total";

    /** The operation classes charted separately. */
    public enum Op {
        CONNECT("connect"),
        READ("read"),
        WRITE("write");

        private final String tag;

        Op(String tag) {
            this.tag = tag;
        }
    }

    /**
     * Why an operation failed. Kept deliberately small and closed: every distinct value multiplies the number
     * of time series, and an unbounded label (a raw exception message, say) would make the series count grow
     * without limit.
     */
    public enum Cause {
        /** The operation did not complete within its deadline. */
        TIMEOUT("timeout"),
        /** The link was gone when the operation was attempted, or dropped underneath it. */
        DISCONNECTED("disconnected"),
        /** The controller refused the command (the CSR {@code COMMAND_DISALLOWED} wedge). */
        COMMAND_DISALLOWED("command_disallowed"),
        /** Connected, but GATT service discovery did not produce a usable model. */
        RESOLVE_FAILED("resolve_failed"),
        /** A read returned a zero-length value — the Direct-BT receive-path-death signature. */
        EMPTY_READ("empty_read"),
        /** The native write call reported failure. */
        WRITE_REJECTED("write_rejected"),
        /** Anything else the native stack threw. */
        NATIVE_ERROR("native_error");

        private final String tag;

        Cause(String tag) {
            this.tag = tag;
        }
    }

    /**
     * Which phase of the connect procedure a duration refers to. Establishing the link and resolving GATT have
     * different failure signatures and very different latencies, so averaging them together would hide both.
     */
    public enum Phase {
        /** Connect issued until the controller reports the link established. */
        LINK("link"),
        /** Link established until the GATT model is resolved and usable. */
        GATT("gatt"),
        /** Not part of the connect procedure (reads and writes). */
        NONE("none");

        private final String tag;

        Phase(String tag) {
            this.tag = tag;
        }
    }

    /**
     * Shared sink for devices that are not instrumented (no configured Thing). Records into a registry with no
     * delegates, so every call is a cheap no-op and callers need no null checks.
     */
    public static final DeviceMetrics NOOP = new DeviceMetrics(new CompositeMeterRegistry(), "", "", "");

    private final MeterRegistry registry;
    private final Tags baseTags;
    private final List<Meter.Id> registered = new ArrayList<>();
    private final AtomicInteger up = new AtomicInteger();

    /**
     * @param registry the openHAB core registry to publish into
     * @param label the device Thing's human label, used as the {@code device} tag so dashboards read naturally
     * @param address the Bluetooth address, so the series survives a rename
     * @param adapter the owning adapter, so a fleet split across adapters stays distinguishable
     */
    public DeviceMetrics(MeterRegistry registry, String label, String address, String adapter) {
        this.registry = registry;
        this.baseTags = Tags.of("device", label, "address", address, "adapter", adapter);

        // Availability gauge. Strong reference to our own AtomicInteger (not to the device), so the gauge
        // cannot pin a disposed device object in the registry.
        register(Gauge.builder(UP, up, AtomicInteger::get)
                .description("1 when the device is connected and its GATT model is resolved, else 0").tags(baseTags)
                .strongReference(true).register(registry));

        register(Counter.builder(CONNECT_ATTEMPTS).description("Connect attempts issued to the controller")
                .tags(baseTags).register(registry));

        // Pre-register the per-op meters so a device that never succeeds still reports a zero series.
        for (Op op : Op.values()) {
            register(Counter.builder(OPS).description("Operations attempted").tags(baseTags.and("op", op.tag))
                    .register(registry));
        }
    }

    private void register(Meter meter) {
        registered.add(meter.getId());
    }

    /** Records the current availability sample: {@code true} iff connected AND the GATT model is resolved. */
    public void setUp(boolean value) {
        up.set(value ? 1 : 0);
    }

    /** Convenience for callers that hold the two conditions separately. */
    public void setUp(BooleanSupplier connected, BooleanSupplier gattResolved) {
        setUp(connected.getAsBoolean() && gattResolved.getAsBoolean());
    }

    /** Counts a connect attempt issued to the controller (the denominator for connect churn). */
    public void connectAttempted() {
        registry.counter(CONNECT_ATTEMPTS, baseTags).increment();
    }

    /**
     * Counts one link actually established. Together with {@link #disconnected} this answers "how many times
     * did this device connect, and how many times did it have to reconnect" — the plain question that the
     * multi-purpose {@code op.total{op="connect"}} counter cannot.
     */
    public void connected() {
        registry.counter(CONNECTS, baseTags).increment();
    }

    /**
     * Counts one established link being lost. Only called for links that were up: a failed connect attempt is
     * not a disconnect, so this stays a true count of "connections that dropped".
     */
    public void disconnected() {
        registry.counter(DISCONNECTS, baseTags).increment();
    }

    /** Counts one completed operation of the given class, successful or not. */
    public void operation(Op op) {
        registry.counter(OPS, baseTags.and("op", op.tag)).increment();
    }

    /**
     * Counts one failed operation. Call {@link #operation} as well — {@code op.total} is the denominator, so
     * reliability is {@code (total - errors) / total}.
     */
    public void failure(Op op, Cause cause) {
        registry.counter(ERRORS, baseTags.and("op", op.tag).and("cause", cause.tag)).increment();
    }

    /** Records how long an operation took. Use {@link Phase#NONE} for reads and writes. */
    public void record(Op op, Phase phase, long nanos) {
        timer(op, phase).record(nanos, TimeUnit.NANOSECONDS);
    }

    private Timer timer(Op op, Phase phase) {
        return Timer.builder(DURATION).description("Operation duration")
                .tags(baseTags.and("op", op.tag).and("phase", phase.tag))
                // Buckets, not client-side percentiles: only buckets can be re-aggregated over an arbitrary
                // window, which is what a "p95 over the last 30 days" panel needs.
                .publishPercentileHistogram()
                // BLE operations range from a few ms to the multi-second connect deadline; clamping the bucket
                // range keeps the series count sane while still covering both ends.
                .minimumExpectedValue(Duration.ofMillis(1)).maximumExpectedValue(Duration.ofSeconds(30))
                .register(registry);
    }

    // --- control-plane (FSM) recording ----------------------------------------------------------------

    /**
     * Adds the residency of a finished state to the control plane's time budget.
     * <p>
     * Recorded in SECONDS as a counter rather than as a histogram: the question this answers is "what fraction of
     * setup time went to waiting for the radio versus the peer", which is a ratio of sums
     * ({@code sum by (waiting_on) (rate(...))}), not a percentile.
     */
    public void recordStateTime(String state, String waitingOn, long millis) {
        registry.counter(STATE_SECONDS, baseTags.and("state", state).and("waiting_on", waitingOn))
                .increment(millis / 1000.0);
    }

    /** Counts entry into a state, so a long residency can be told apart from many short ones. */
    public void countTransition(String state, String waitingOn) {
        registry.counter(TRANSITIONS, baseTags.and("state", state).and("waiting_on", waitingOn)).increment();
    }

    public void countProcedureStart(String procedure) {
        registry.counter(PROCEDURE_STARTS, baseTags.and("procedure", procedure)).increment();
    }

    /** Counts how a procedure ended. Success rate per procedure = handed_off+succeeded over the total. */
    public void countProcedureOutcome(String procedure, String outcome) {
        registry.counter(PROCEDURE_OUTCOMES, baseTags.and("procedure", procedure).and("outcome", outcome)).increment();
    }

    /** Times a whole procedure end to end, so p95 is per procedure rather than per native call. */
    public void recordProcedureDuration(String procedure, String outcome, long nanos) {
        Timer.builder(PROCEDURE_DURATION).description("Device procedure duration")
                .tags(baseTags.and("procedure", procedure).and("outcome", outcome)).publishPercentileHistogram()
                // Up to 60s: the connect-lease wait alone may legitimately run to a 45s deadline.
                .minimumExpectedValue(Duration.ofMillis(1)).maximumExpectedValue(Duration.ofSeconds(60))
                .register(registry).record(nanos, TimeUnit.NANOSECONDS);
    }

    /**
     * Counts a control-plane generation advance. Every increment fences the in-flight events and effects, i.e. it
     * is a restart: {@code rate()} over this is the clearest measure of retry churn.
     */
    public void countGeneration(long generation) {
        registry.counter(GENERATIONS, baseTags).increment();
    }

    /**
     * Counts a blown residency deadline, tagged by what the procedure was waiting on when it blew. That tag is
     * the tuning signal: CONNECT_LEASE expiries mean the radio never freed up (an arbitration problem), while
     * NATIVE_CONNECT expiries mean the peer never answered (a link problem).
     */
    public void countDeadline(String procedure, String waitingOn) {
        registry.counter(DEADLINES, baseTags.and("procedure", procedure).and("waiting_on", waitingOn)).increment();
    }

    /**
     * Removes every meter this device registered. Without it a deleted or aged-out Thing keeps publishing a
     * stale series (and its last value) for as long as the runtime lives.
     */
    @Override
    public void close() {
        if (this == NOOP) {
            return; // the shared no-op sink outlives every device
        }
        up.set(0);
        registry.getMeters().stream().map(Meter::getId)
                .filter(id -> id.getName().startsWith(PREFIX) && matchesBaseTags(id)).forEach(registry::remove);
        registered.clear();
    }

    private boolean matchesBaseTags(Meter.Id id) {
        List<io.micrometer.core.instrument.Tag> tags = id.getTags();
        for (io.micrometer.core.instrument.Tag tag : baseTags) {
            if (!tags.contains(tag)) {
                return false;
            }
        }
        return true;
    }
}
