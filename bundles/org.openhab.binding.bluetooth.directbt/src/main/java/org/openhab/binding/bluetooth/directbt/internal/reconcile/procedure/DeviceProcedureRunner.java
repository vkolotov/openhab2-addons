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
package org.openhab.binding.bluetooth.directbt.internal.reconcile.procedure;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.device.DeviceActor;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.event.DeviceEffect;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.event.DeviceEvent;

/**
 * Minimal actor-side procedure runner. It consumes internal procedure-handoff effects and leaves only external
 * effects for the future native executor or adapter coordinator.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
public final class DeviceProcedureRunner {
    private final DeviceActor actor;
    private final DeviceProcedureFactory procedureFactory;
    private final List<DeviceEffect> externalEffects = new ArrayList<>();

    public DeviceProcedureRunner(DeviceActor actor, DeviceProcedureFactory procedureFactory) {
        this.actor = actor;
        this.procedureFactory = procedureFactory;
    }

    public void start(DeviceProcedure procedure, String cause) {
        actor.startProcedure(procedure, cause);
        drainAndInterpret();
    }

    public void submit(DeviceEvent event) {
        actor.submit(event);
        drainAndInterpret();
    }

    public void tick() {
        actor.tick();
        drainAndInterpret();
    }

    public List<DeviceEffect> drainEffects() {
        List<DeviceEffect> drained = List.copyOf(externalEffects);
        externalEffects.clear();
        return drained;
    }

    private void drainAndInterpret() {
        List<DeviceEffect> pending = actor.drainEffects();
        while (!pending.isEmpty()) {
            for (DeviceEffect effect : pending) {
                if (!handleInternalEffect(effect)) {
                    addExternalEffectIfCurrent(effect);
                }
            }
            pending = actor.drainEffects();
        }
    }

    private void addExternalEffectIfCurrent(DeviceEffect effect) {
        if (effect.generation() == actor.diagnostics().generation()) {
            externalEffects.add(effect);
        }
    }

    private boolean handleInternalEffect(DeviceEffect effect) {
        DeviceProcedureName procedureName = effect.operation().targetProcedure();
        if (procedureName == null) {
            return false;
        }
        DeviceProcedure procedure = procedureFactory.create(procedureName);
        if (procedure == null) {
            return false;
        }
        actor.handoffProcedure(procedure, effect.operation().name());
        return true;
    }

    @FunctionalInterface
    public interface DeviceProcedureFactory {
        @Nullable
        DeviceProcedure create(DeviceProcedureName procedureName);
    }
}
