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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * Snapshot of actor progress for logs, Thing diagnostics, and tests.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
public final class DeviceActorDiagnostics {
    private final String deviceId;
    private final long generation;
    private final DeviceActorState state;
    private final DeviceWaitingOn waitingOn;
    private final long stateStartedAt;
    private final long timeInStateMs;
    private final String lastCause;
    private final @Nullable DeviceProcedureName activeProcedureName;

    DeviceActorDiagnostics(String deviceId, long generation, DeviceActorState state, DeviceWaitingOn waitingOn,
            long stateStartedAt, long timeInStateMs, String lastCause,
            @Nullable DeviceProcedureName activeProcedureName) {
        this.deviceId = deviceId;
        this.generation = generation;
        this.state = state;
        this.waitingOn = waitingOn;
        this.stateStartedAt = stateStartedAt;
        this.timeInStateMs = timeInStateMs;
        this.lastCause = lastCause;
        this.activeProcedureName = activeProcedureName;
    }

    String deviceId() {
        return deviceId;
    }

    public long generation() {
        return generation;
    }

    DeviceActorState state() {
        return state;
    }

    public String stateName() {
        return state.name();
    }

    DeviceWaitingOn waitingOn() {
        return waitingOn;
    }

    public String waitingOnName() {
        return waitingOn.name();
    }

    public long stateStartedAt() {
        return stateStartedAt;
    }

    public long timeInStateMs() {
        return timeInStateMs;
    }

    public String lastCause() {
        return lastCause;
    }

    @Nullable
    DeviceProcedureName activeProcedureName() {
        return activeProcedureName;
    }

    public String summary() {
        return "state=" + state + " waitingOn=" + waitingOn + " generation=" + generation + " timeInStateMs="
                + timeInStateMs + " cause=" + lastCause + " activeProcedure=" + activeProcedureName;
    }
}
