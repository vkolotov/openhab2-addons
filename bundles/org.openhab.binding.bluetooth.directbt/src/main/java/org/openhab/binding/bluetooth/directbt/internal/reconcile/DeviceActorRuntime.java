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
    private final Consumer<DeviceEvent> eventObserver;
    private final List<DeviceEvent> pendingEvents = new ArrayList<>();
    private final List<DeviceEffect> unhandledEffects = new ArrayList<>();

    DeviceActorRuntime(DeviceActor actor, DeviceProcedureRunner.DeviceProcedureFactory procedureFactory,
            BooleanSupplier scanIsOff, DevicePort port) {
        this(actor, procedureFactory, scanIsOff, port, event -> {
        });
    }

    DeviceActorRuntime(DeviceActor actor, DeviceProcedureRunner.DeviceProcedureFactory procedureFactory,
            BooleanSupplier scanIsOff, DevicePort port, Consumer<DeviceEvent> eventObserver) {
        this.actor = actor;
        this.runner = new DeviceProcedureRunner(actor, procedureFactory);
        this.connectLeaseExecutor = new ConnectLeaseEffectExecutor(scanIsOff, this::enqueue);
        this.portEffectExecutor = new DevicePortEffectExecutor(port, this::enqueue);
        this.eventObserver = eventObserver;
    }

    void start(DeviceProcedure procedure, String cause) {
        runner.start(procedure, cause);
        pump();
    }

    void submit(DeviceEvent event) {
        runner.submit(event);
        pump();
    }

    void tick() {
        connectLeaseExecutor.tick(actor.diagnostics().generation());
        runner.tick();
        pump();
    }

    List<DeviceEffect> drainUnhandledEffects() {
        List<DeviceEffect> drained = List.copyOf(unhandledEffects);
        unhandledEffects.clear();
        return drained;
    }

    DeviceActorDiagnostics diagnostics() {
        return actor.diagnostics();
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
        if (portEffectExecutor.execute(effect)) {
            return;
        }
        unhandledEffects.add(effect);
    }
}
