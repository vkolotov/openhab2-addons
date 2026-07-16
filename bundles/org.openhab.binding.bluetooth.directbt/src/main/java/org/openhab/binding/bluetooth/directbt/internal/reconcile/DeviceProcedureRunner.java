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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * Minimal actor-side procedure runner. It consumes internal procedure-handoff effects and leaves only external
 * effects for the future native executor or adapter coordinator.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
final class DeviceProcedureRunner {
    private static final String START_PROCEDURE_PREFIX = "startProcedure:";

    private final DeviceActor actor;
    private final DeviceProcedureFactory procedureFactory;
    private final List<DeviceEffect> externalEffects = new ArrayList<>();

    DeviceProcedureRunner(DeviceActor actor, DeviceProcedureFactory procedureFactory) {
        this.actor = actor;
        this.procedureFactory = procedureFactory;
    }

    void start(DeviceProcedure procedure, String cause) {
        actor.startProcedure(procedure, cause);
        drainAndInterpret();
    }

    void submit(DeviceEvent event) {
        actor.submit(event);
        drainAndInterpret();
    }

    void tick() {
        actor.tick();
        drainAndInterpret();
    }

    List<DeviceEffect> drainEffects() {
        List<DeviceEffect> drained = List.copyOf(externalEffects);
        externalEffects.clear();
        return drained;
    }

    private void drainAndInterpret() {
        List<DeviceEffect> pending = actor.drainEffects();
        while (!pending.isEmpty()) {
            for (DeviceEffect effect : pending) {
                if (!handleInternalEffect(effect)) {
                    externalEffects.add(effect);
                }
            }
            pending = actor.drainEffects();
        }
    }

    private boolean handleInternalEffect(DeviceEffect effect) {
        String operation = effect.operation();
        if (!operation.startsWith(START_PROCEDURE_PREFIX)) {
            return false;
        }
        DeviceProcedureName procedureName = DeviceProcedureName.valueOf(operation.substring(START_PROCEDURE_PREFIX.length()));
        DeviceProcedure procedure = procedureFactory.create(procedureName);
        if (procedure == null) {
            return false;
        }
        actor.handoffProcedure(procedure, operation);
        return true;
    }

    @FunctionalInterface
    interface DeviceProcedureFactory {
        @Nullable
        DeviceProcedure create(DeviceProcedureName procedureName);
    }
}
