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
package org.openhab.binding.bluetooth.directbt.internal.reconcile;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.slf4j.Logger;

/**
 * One serialized device control plane: hosts the active {@link DeviceProcedure}, owns the generation counter
 * that fences stale events, times each waiting phase against the procedure's residency deadline, and collects
 * the effects procedures emit for the runtime's executors.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
final class DeviceActor {
    private final String deviceId;
    private final Logger logger;
    private final Clock clock;
    private final List<DeviceEffect> effects = new ArrayList<>();

    private long generation;
    private boolean wantedOnline;
    private DeviceActorState state = DeviceActorState.IDLE_DISABLED;
    private DeviceWaitingOn waitingOn = DeviceWaitingOn.NOTHING;
    private long stateStartedAt;
    private String lastCause = "initial";
    private @Nullable DeviceProcedure activeProcedure;
    private boolean deadlineReported;

    DeviceActor(String deviceId, Logger logger, Clock clock) {
        this.deviceId = deviceId;
        this.logger = logger;
        this.clock = clock;
        this.stateStartedAt = clock.millis();
    }

    void startProcedure(DeviceProcedure procedure, String cause) {
        ensureDeadline(procedure);
        DeviceProcedure previous = activeProcedure;
        DeviceProcedureContext ctx = context();
        if (previous != null) {
            previous.cancel("replaced by " + procedure.name(), ctx);
        }
        activeProcedure = procedure;
        generation++;
        deadlineReported = false;
        transitionTo(procedure.actorState(), procedure.waitingOn(), cause);
        procedure.start(context());
    }

    void handoffProcedure(DeviceProcedure procedure, String cause) {
        ensureDeadline(procedure);
        activeProcedure = procedure;
        deadlineReported = false;
        transitionTo(procedure.actorState(), procedure.waitingOn(), cause);
        procedure.start(context());
    }

    void submit(DeviceEvent event) {
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
                generation++;
                deadlineReported = false;
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

    void tick() {
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
            procedure.onEvent(new DeviceEvent.ProcedureDeadlineExpired(generation, procedure.name(), elapsed),
                    context());
        }
    }

    List<DeviceEffect> drainEffects() {
        List<DeviceEffect> drained = List.copyOf(effects);
        effects.clear();
        return drained;
    }

    DeviceActorDiagnostics diagnostics() {
        long now = clock.millis();
        DeviceProcedure procedure = activeProcedure;
        return new DeviceActorDiagnostics(deviceId, generation, state, waitingOn, stateStartedAt, now - stateStartedAt,
                lastCause, procedure == null ? null : procedure.name());
    }

    private void invalidateForAdapterReset(String cause) {
        // The adapter reset itself invalidates every native handle. Do not run procedure cancellation effects here:
        // they are per-device native cleanup calls, and executing them on the reconcile thread before reset can block
        // behind the same native layer the reset is meant to recover.
        activeProcedure = null;
        effects.clear();
        generation++;
        deadlineReported = false;
        transitionTo(DeviceActorState.BACKING_OFF, DeviceWaitingOn.ADAPTER_RESET, cause);
    }

    private void transitionTo(DeviceActorState next, DeviceWaitingOn nextWaitingOn, String cause) {
        if (state != next || waitingOn != nextWaitingOn) {
            logger.debug("[actor:{}] gen={} {} -> {} cause={} waitingOn={}", deviceId, generation, state, next, cause,
                    nextWaitingOn);
        }
        state = next;
        waitingOn = nextWaitingOn;
        stateStartedAt = clock.millis();
        lastCause = cause;
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
