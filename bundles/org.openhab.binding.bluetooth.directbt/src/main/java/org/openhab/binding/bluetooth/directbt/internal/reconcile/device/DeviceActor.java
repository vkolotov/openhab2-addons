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

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.event.DeviceEffect;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.event.DeviceEvent;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.procedure.DeviceProcedure;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.procedure.DeviceProcedureContext;
import org.slf4j.Logger;

/**
 * One serialized device control plane: hosts the active {@link DeviceProcedure}, owns the generation counter
 * that fences stale events, times each waiting phase against the procedure's residency deadline, and collects
 * the effects procedures emit for the runtime's executors.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
public final class DeviceActor {
    private final String deviceId;
    private final Logger logger;
    private final Clock clock;
    private final DeviceActorListener listener;
    private final List<DeviceEffect> effects = new ArrayList<>();

    private long generation;
    private boolean wantedOnline;
    private DeviceActorState state = DeviceActorState.IDLE_DISABLED;
    private DeviceWaitingOn waitingOn = DeviceWaitingOn.NOTHING;
    private long stateStartedAt;
    private String lastCause = "initial";
    private @Nullable DeviceProcedure activeProcedure;
    private boolean deadlineReported;
    // When the active procedure started, so it can be timed end to end rather than per state.
    private long procedureStartedAt;

    public DeviceActor(String deviceId, Logger logger, Clock clock) {
        this(deviceId, logger, clock, DeviceActorListener.NOOP);
    }

    public DeviceActor(String deviceId, Logger logger, Clock clock, DeviceActorListener listener) {
        this.deviceId = deviceId;
        this.logger = logger;
        this.clock = clock;
        this.listener = listener;
        this.stateStartedAt = clock.millis();
    }

    public void startProcedure(DeviceProcedure procedure, String cause) {
        ensureDeadline(procedure);
        DeviceProcedure previous = activeProcedure;
        DeviceProcedureContext ctx = context();
        if (previous != null) {
            previous.cancel("replaced by " + procedure.name(), ctx);
            finishProcedure(previous, DeviceProcedureOutcome.CANCELLED);
        }
        activeProcedure = procedure;
        advanceGeneration(cause);
        deadlineReported = false;
        beginProcedure(procedure, cause);
        transitionTo(procedure.actorState(), procedure.waitingOn(), cause);
        procedure.start(context());
    }

    public void handoffProcedure(DeviceProcedure procedure, String cause) {
        ensureDeadline(procedure);
        // A handoff is the healthy path: the previous procedure completed its part of the sequence.
        finishProcedure(activeProcedure, DeviceProcedureOutcome.HANDED_OFF);
        activeProcedure = procedure;
        deadlineReported = false;
        beginProcedure(procedure, cause);
        transitionTo(procedure.actorState(), procedure.waitingOn(), cause);
        procedure.start(context());
    }

    private void beginProcedure(DeviceProcedure procedure, String cause) {
        procedureStartedAt = clock.millis();
        notifyListener(() -> listener.onProcedureStarted(procedure.name(), cause));
    }

    public void submit(DeviceEvent event) {
        if (event instanceof DeviceEvent.WantedOnline) {
            wantedOnline = true;
            if (activeProcedure == null && state == DeviceActorState.IDLE_DISABLED) {
                transitionTo(DeviceActorState.DISCOVERING, DeviceWaitingOn.NATIVE_HANDLE, event.kind());
            }
            return;
        }
        if (event instanceof DeviceEvent.WantedOffline) {
            wantedOnline = false;
            DeviceProcedure procedure = activeProcedure;
            if (procedure == null) {
                transitionTo(DeviceActorState.IDLE_DISABLED, DeviceWaitingOn.NOTHING, event.kind());
            } else {
                activeProcedure = null;
                advanceGeneration(event.kind());
                deadlineReported = false;
                finishProcedure(procedure, DeviceProcedureOutcome.CANCELLED);
                transitionTo(DeviceActorState.DISCONNECTING, DeviceWaitingOn.DISCONNECT, event.kind());
                procedure.cancel(event.kind(), context());
            }
            return;
        }
        if (event instanceof DeviceEvent.AdapterResetStarted) {
            invalidateForAdapterReset(event.kind());
            return;
        }
        if (event instanceof DeviceEvent.AdapterResetCompleted) {
            invalidateForAdapterReset(event.kind());
            transitionTo(wantedOnline ? DeviceActorState.DISCOVERING : DeviceActorState.IDLE_DISABLED,
                    wantedOnline ? DeviceWaitingOn.NATIVE_HANDLE : DeviceWaitingOn.NOTHING, event.kind());
            return;
        }
        if (event.generationScoped() && event.generation() != generation) {
            logger.debug("[actor:{}] ignoring stale {} for generation {} (current {})", deviceId, event.kind(),
                    event.generation(), generation);
            return;
        }
        DeviceProcedure procedure = activeProcedure;
        if (procedure != null) {
            procedure.onEvent(event, context());
        }
    }

    public void tick() {
        DeviceProcedure procedure = activeProcedure;
        if (procedure == null || deadlineReported) {
            return;
        }
        long maxResidencyMs = procedure.maxResidencyMs(waitingOn);
        if (maxResidencyMs <= 0) {
            return;
        }
        long elapsed = clock.millis() - stateStartedAt;
        if (elapsed >= maxResidencyMs) {
            deadlineReported = true;
            logger.warn("[actor:{}] {} exceeded deadline after {}ms in {} waitingOn={}", deviceId, procedure.name(),
                    elapsed, state, waitingOn);
            // Which waitingOn blew the deadline is the tuning signal: a CONNECT_LEASE expiry means the radio
            // never became available, while NATIVE_CONNECT means the peer never answered. Same procedure,
            // opposite remedies.
            DeviceWaitingOn blockedOn = waitingOn;
            notifyListener(() -> listener.onDeadlineExceeded(procedure.name(), blockedOn, elapsed));
            finishProcedure(procedure, DeviceProcedureOutcome.DEADLINE_EXPIRED);
            procedure.onEvent(new DeviceEvent.ProcedureDeadlineExpired(generation, procedure.name(), elapsed),
                    context());
        }
    }

    public List<DeviceEffect> drainEffects() {
        List<DeviceEffect> drained = List.copyOf(effects);
        effects.clear();
        return drained;
    }

    public DeviceActorDiagnostics diagnostics() {
        long now = clock.millis();
        DeviceProcedure procedure = activeProcedure;
        return new DeviceActorDiagnostics(deviceId, generation, state, waitingOn, stateStartedAt, now - stateStartedAt,
                lastCause, procedure == null ? null : procedure.name());
    }

    private void invalidateForAdapterReset(String cause) {
        // The adapter reset itself invalidates every native handle. Do not run procedure cancellation effects here:
        // they are per-device native cleanup calls, and executing them on the reconcile thread before reset can block
        // behind the same native layer the reset is meant to recover.
        finishProcedure(activeProcedure, DeviceProcedureOutcome.ADAPTER_RESET);
        activeProcedure = null;
        effects.clear();
        advanceGeneration(cause);
        deadlineReported = false;
        transitionTo(DeviceActorState.BACKING_OFF, DeviceWaitingOn.ADAPTER_RESET, cause);
    }

    private void transitionTo(DeviceActorState next, DeviceWaitingOn nextWaitingOn, String cause) {
        if (state != next || waitingOn != nextWaitingOn) {
            logger.debug("[actor:{}] gen={} {} -> {} cause={} waitingOn={}", deviceId, generation, state, next, cause,
                    nextWaitingOn);
        }
        long now = clock.millis();
        DeviceProcedure procedure = activeProcedure;
        notifyListener(() -> listener.onTransition(state, waitingOn, next, nextWaitingOn, now - stateStartedAt, cause,
                procedure == null ? null : procedure.name()));
        state = next;
        waitingOn = nextWaitingOn;
        stateStartedAt = now;
        lastCause = cause;
    }

    /**
     * Runs an observer callback without ever letting it affect the state machine. A metrics sink must not be able
     * to break device control, so a throwing listener is logged and swallowed.
     */
    private void notifyListener(Runnable notification) {
        try {
            notification.run();
        } catch (RuntimeException e) {
            logger.debug("[actor:{}] actor listener threw; ignoring", deviceId, e);
        }
    }

    /** Records the end of the active procedure and notifies the listener. */
    private void finishProcedure(@Nullable DeviceProcedure procedure, DeviceProcedureOutcome outcome) {
        if (procedure == null) {
            return;
        }
        long duration = clock.millis() - procedureStartedAt;
        notifyListener(() -> listener.onProcedureFinished(procedure.name(), outcome, duration));
    }

    /** Advances the fencing generation and notifies the listener; every increment is a control-plane restart. */
    private void advanceGeneration(String cause) {
        generation++;
        long current = generation;
        notifyListener(() -> listener.onGenerationAdvanced(current, cause));
    }

    private DeviceProcedureContext context() {
        return new DeviceProcedureContext() {
            @Override
            public String deviceId() {
                return deviceId;
            }

            @Override
            public long generation() {
                return generation;
            }

            @Override
            public void emit(DeviceEffect effect) {
                if (effect.generation() != generation) {
                    throw new IllegalArgumentException("effect generation " + effect.generation()
                            + " does not match current generation " + generation);
                }
                effects.add(effect);
            }

            @Override
            public void transitionTo(DeviceActorState state, DeviceWaitingOn waitingOn, String cause) {
                DeviceActor.this.transitionTo(state, waitingOn, cause);
            }
        };
    }

    private static void ensureDeadline(DeviceProcedure procedure) {
        DeviceActorState state = procedure.actorState();
        if (state != DeviceActorState.IDLE_DISABLED && state != DeviceActorState.ONLINE
                && procedure.maxResidencyMs() <= 0) {
            throw new IllegalArgumentException(procedure.name() + " in " + state + " has no deadline");
        }
    }
}
