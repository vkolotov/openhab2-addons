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

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * Composes the actor, procedure runner, and first effect executors into one pumpable control-plane runtime. This
 * class still has no thread of its own; callers decide when to start procedures, submit native events, and tick.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
final class DeviceActorRuntime {
    private final DeviceActor actor;
    private final DeviceProcedureRunner runner;
    private final ConnectLeaseEffectExecutor connectLeaseExecutor;
    private final DevicePortEffectExecutor portEffectExecutor;
    private final @Nullable SettleTimerEffectExecutor settleTimerExecutor;
    private final Consumer<DeviceEvent> eventObserver;
    private final @Nullable DeviceBackoffPolicy backoffPolicy;
    private final Consumer<DeviceActorDiagnostics> backoffObserver;
    private final List<DeviceEvent> pendingEvents = new ArrayList<>();
    private final List<DeviceEffect> unhandledEffects = new ArrayList<>();
    // Epoch millis of the last CONNECT procedure start (0 = never): the runtime-owned retry-pacing stamp.
    private long lastConnectStartedAt;

    DeviceActorRuntime(DeviceActor actor, DeviceProcedureRunner.DeviceProcedureFactory procedureFactory,
            BooleanSupplier scanIsOff, DevicePort port) {
        this(actor, procedureFactory, scanIsOff, port, event -> {
        });
    }

    DeviceActorRuntime(DeviceActor actor, DeviceProcedureRunner.DeviceProcedureFactory procedureFactory,
            BooleanSupplier scanIsOff, DevicePort port, Consumer<DeviceEvent> eventObserver) {
        this(actor, procedureFactory, scanIsOff, port, eventObserver, null, diagnostics -> {
        });
    }

    DeviceActorRuntime(DeviceActor actor, DeviceProcedureRunner.DeviceProcedureFactory procedureFactory,
            BooleanSupplier scanIsOff, DevicePort port, Consumer<DeviceEvent> eventObserver,
            @Nullable DeviceBackoffPolicy backoffPolicy, Consumer<DeviceActorDiagnostics> backoffObserver) {
        this(actor, procedureFactory, scanIsOff, port, eventObserver, null, backoffPolicy, backoffObserver);
    }

    DeviceActorRuntime(DeviceActor actor, DeviceProcedureRunner.DeviceProcedureFactory procedureFactory,
            BooleanSupplier scanIsOff, DevicePort port, Consumer<DeviceEvent> eventObserver,
            @Nullable SettleTimerEffectExecutor settleTimerExecutor, @Nullable DeviceBackoffPolicy backoffPolicy,
            Consumer<DeviceActorDiagnostics> backoffObserver) {
        this.actor = actor;
        this.runner = new DeviceProcedureRunner(actor, procedureFactory);
        this.connectLeaseExecutor = new ConnectLeaseEffectExecutor(scanIsOff, this::enqueue);
        this.portEffectExecutor = new DevicePortEffectExecutor(port, this::enqueue);
        this.settleTimerExecutor = settleTimerExecutor;
        this.eventObserver = eventObserver;
        this.backoffPolicy = backoffPolicy;
        this.backoffObserver = backoffObserver;
    }

    void start(DeviceProcedure procedure, String cause) {
        runner.start(procedure, cause);
        if (procedure.name() == DeviceProcedureName.CONNECT) {
            // The actor stamped stateStartedAt at the CONNECT transition; reuse it so pacing and the actor
            // share one clock.
            lastConnectStartedAt = actor.diagnostics().stateStartedAt();
        }
        pump();
        applyBackoffPolicy();
    }

    /** Epoch millis of the last CONNECT procedure start, 0 if never — the attempt retry-pacing stamp. */
    long lastConnectStartedAt() {
        return lastConnectStartedAt;
    }

    void submit(DeviceEvent event) {
        runner.submit(event);
        pump();
        applyBackoffPolicy();
    }

    void tick() {
        tick(false);
    }

    void tick(boolean procedureDeadlinePaused) {
        tickSettleTimer();
        connectLeaseExecutor.tick(actor.diagnostics().generation());
        // Deliver any expired-timer events BEFORE judging procedure residency: on a sparse tick (paused
        // adapter, long backoff) both the settle timer (500 ms) and the settle deadline (5 s) can be past due
        // at once, and the deadline must not tear down a link whose timer-driven handoff is already earned
        // (evidence preempts waits).
        pump();
        if (!procedureDeadlinePaused) {
            runner.tick();
        }
        pump();
        applyBackoffPolicy();
    }

    List<DeviceEffect> drainUnhandledEffects() {
        List<DeviceEffect> drained = List.copyOf(unhandledEffects);
        unhandledEffects.clear();
        return drained;
    }

    DeviceActorDiagnostics diagnostics() {
        return actor.diagnostics();
    }

    boolean isActive(DeviceProcedureName procedureName) {
        return procedureName == diagnostics().activeProcedureName();
    }

    long generation() {
        return diagnostics().generation();
    }

    void shadowObserve(DeviceActorState state, DeviceWaitingOn waitingOn, String cause) {
        actor.shadowOverride(state, waitingOn, cause);
        pendingEvents.clear();
        unhandledEffects.clear();
    }

    private void enqueue(DeviceEvent event) {
        eventObserver.accept(event);
        pendingEvents.add(event);
    }

    private void pump() {
        boolean progressed;
        do {
            progressed = submitPendingEvents();
            progressed = dispatchEffects() || progressed;
        } while (progressed);
    }

    private boolean submitPendingEvents() {
        if (pendingEvents.isEmpty()) {
            return false;
        }
        List<DeviceEvent> events = List.copyOf(pendingEvents);
        pendingEvents.clear();
        for (DeviceEvent event : events) {
            runner.submit(event);
        }
        return true;
    }

    private boolean dispatchEffects() {
        List<DeviceEffect> effects = runner.drainEffects();
        if (effects.isEmpty()) {
            return false;
        }
        for (DeviceEffect effect : effects) {
            dispatch(effect);
        }
        return true;
    }

    private void dispatch(DeviceEffect effect) {
        if (connectLeaseExecutor.execute(effect)) {
            return;
        }
        SettleTimerEffectExecutor settleTimer = settleTimerExecutor;
        if (settleTimer != null && settleTimer.execute(effect)) {
            return;
        }
        if (portEffectExecutor.execute(effect)) {
            return;
        }
        unhandledEffects.add(effect);
    }

    private void tickSettleTimer() {
        SettleTimerEffectExecutor settleTimer = settleTimerExecutor;
        if (settleTimer == null) {
            return;
        }
        DeviceEvent event = settleTimer.tick(actor.diagnostics().generation());
        if (event != null) {
            enqueue(event);
        }
    }

    private void applyBackoffPolicy() {
        DeviceBackoffPolicy policy = backoffPolicy;
        if (policy == null) {
            return;
        }
        DeviceActorDiagnostics diagnostics = actor.diagnostics();
        if (policy.apply(diagnostics)) {
            backoffObserver.accept(diagnostics);
        }
    }
}
