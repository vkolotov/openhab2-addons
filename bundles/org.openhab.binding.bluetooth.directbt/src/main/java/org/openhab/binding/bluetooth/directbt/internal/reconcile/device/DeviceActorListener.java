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
package org.openhab.binding.bluetooth.directbt.internal.reconcile.device;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.procedure.DeviceProcedureName;

/**
 * Observes an actor's control-plane transitions. Purely passive: implementations must not influence the state
 * machine, must not throw, and must not block — they run inline on the reconcile tick thread.
 * <p>
 * This is the single funnel for control-plane observability. Every state change flows through
 * {@code DeviceActor.transitionTo()}, so one hook yields time-in-state, procedure outcomes and retry churn
 * together, rather than scattering counters across each procedure.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
public interface DeviceActorListener {

    /** A listener that records nothing, so callers never need a null check. */
    DeviceActorListener NOOP = new DeviceActorListener() {
    };

    /**
     * The actor left one state for another.
     * <p>
     * {@code timeInPreviousStateMs} is the exact residency of the state being left, which is what makes
     * "where does connection setup time actually go" answerable: waiting for the radio
     * ({@link DeviceWaitingOn#CONNECT_LEASE}) is a different cost from waiting for the peer
     * ({@link DeviceWaitingOn#NATIVE_CONNECT}) or for GATT ({@link DeviceWaitingOn#GATT_RESOLVE}).
     *
     * @param from the state being left
     * @param fromWaitingOn what the actor was waiting on in that state
     * @param to the state being entered
     * @param toWaitingOn what the actor is now waiting on
     * @param timeInPreviousStateMs how long the actor spent in {@code from}/{@code fromWaitingOn}
     * @param cause the event or reason that triggered the transition
     * @param procedure the procedure active at the moment of the transition, if any
     */
    default void onTransition(DeviceActorState from, DeviceWaitingOn fromWaitingOn, DeviceActorState to,
            DeviceWaitingOn toWaitingOn, long timeInPreviousStateMs, String cause,
            @Nullable DeviceProcedureName procedure) {
    }

    /**
     * A procedure started. Paired with {@link #onProcedureFinished} to time whole procedures rather than
     * individual states.
     */
    default void onProcedureStarted(DeviceProcedureName procedure, String cause) {
    }

    /**
     * A procedure ended.
     *
     * @param outcome how it ended — the difference between a clean handoff, a failure and a blown deadline is
     *            what says whether the deadline constants are tuned correctly
     * @param durationMs total wall time from start to end
     */
    default void onProcedureFinished(DeviceProcedureName procedure, DeviceProcedureOutcome outcome, long durationMs) {
    }

    /**
     * The actor's generation advanced, fencing every in-flight event and effect.
     * <p>
     * Generation churn is the headline "are we thrashing?" signal: each increment is a control-plane restart, so
     * a healthy device's generation is nearly flat while a struggling one climbs steadily.
     */
    default void onGenerationAdvanced(long generation, String cause) {
    }

    /** A procedure blew its residency deadline while waiting on {@code waitingOn}. */
    default void onDeadlineExceeded(DeviceProcedureName procedure, DeviceWaitingOn waitingOn, long elapsedMs) {
    }
}
