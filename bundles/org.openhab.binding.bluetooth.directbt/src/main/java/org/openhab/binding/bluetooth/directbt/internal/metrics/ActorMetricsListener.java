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

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.device.DeviceActorListener;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.device.DeviceActorState;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.device.DeviceProcedureOutcome;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.device.DeviceWaitingOn;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.procedure.DeviceProcedureName;

/**
 * Publishes the device control plane's own vocabulary — actor states, what each state was waiting on, procedure
 * outcomes and generation churn — as metrics.
 * <p>
 * This is the layer that answers <em>"is the transport working efficiently, and what can still be improved"</em>,
 * which the device-facing SLO metrics in {@link DeviceMetrics} cannot: those say whether a device was usable,
 * not where its setup time went or why it had to retry.
 * <p>
 * The three questions it makes directly queryable:
 * <ul>
 * <li><b>Where does time go?</b> {@code state.seconds.total} split by {@code waiting_on} separates waiting for the
 * radio ({@link DeviceWaitingOn#CONNECT_LEASE}) from waiting for the peer ({@link DeviceWaitingOn#NATIVE_CONNECT})
 * and from waiting for GATT ({@link DeviceWaitingOn#GATT_RESOLVE}). Those have opposite remedies, so an aggregate
 * "connect latency" hides the actionable part.</li>
 * <li><b>Are we thrashing?</b> {@code generation.total} counts control-plane restarts. A healthy device's
 * generation is nearly flat; a struggling one climbs steadily even while it still eventually connects.</li>
 * <li><b>Are the deadlines tuned?</b> {@code procedure.total} by outcome, plus {@code deadline.total} by the
 * {@code waiting_on} that blew it, says whether the residency bounds are right or merely surviving.</li>
 * </ul>
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
public class ActorMetricsListener implements DeviceActorListener {

    private final Supplier<DeviceMetrics> metrics;

    /**
     * @param metrics supplies the device's metric set on each callback rather than capturing it once: a device is
     *            only instrumented once it has a configured Thing, and its actor may transition before then.
     */
    public ActorMetricsListener(Supplier<DeviceMetrics> metrics) {
        this.metrics = metrics;
    }

    @Override
    public void onTransition(DeviceActorState from, DeviceWaitingOn fromWaitingOn, DeviceActorState to,
            DeviceWaitingOn toWaitingOn, long timeInPreviousStateMs, String cause,
            @Nullable DeviceProcedureName procedure) {
        // Attribute the elapsed time to the state being LEFT: that is the state whose residency just completed,
        // so the sum over a window is a true time budget of the control plane.
        metrics.get().recordStateTime(from.name(), fromWaitingOn.name(), timeInPreviousStateMs);
        metrics.get().countTransition(to.name(), toWaitingOn.name());
    }

    @Override
    public void onProcedureStarted(DeviceProcedureName procedure, String cause) {
        metrics.get().countProcedureStart(procedure.name());
    }

    @Override
    public void onProcedureFinished(DeviceProcedureName procedure, DeviceProcedureOutcome outcome, long durationMs) {
        metrics.get().countProcedureOutcome(procedure.name(), outcome.name().toLowerCase());
        metrics.get().recordProcedureDuration(procedure.name(), outcome.name().toLowerCase(),
                TimeUnit.MILLISECONDS.toNanos(durationMs));
    }

    @Override
    public void onGenerationAdvanced(long generation, String cause) {
        metrics.get().countGeneration(generation);
    }

    @Override
    public void onDeadlineExceeded(DeviceProcedureName procedure, DeviceWaitingOn waitingOn, long elapsedMs) {
        metrics.get().countDeadline(procedure.name(), waitingOn.name());
    }
}
