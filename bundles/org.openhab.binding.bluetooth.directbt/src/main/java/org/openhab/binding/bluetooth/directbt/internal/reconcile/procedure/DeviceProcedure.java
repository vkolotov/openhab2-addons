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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.device.DeviceActorState;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.device.DeviceWaitingOn;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.event.DeviceEvent;

/**
 * A deadline-bearing BLE control-plane procedure hosted by {@link DeviceActor}.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
public interface DeviceProcedure {
    DeviceProcedureName name();

    DeviceActorState actorState();

    DeviceWaitingOn waitingOn();

    /**
     * @return max silent residency in this procedure; non-idle procedures must return a positive value.
     */
    long maxResidencyMs();

    /**
     * Phase-aware residency bound: the deadline applied while the procedure waits on {@code waitingOn}
     * (the actor's state clock resets on every transition, so each waiting phase is timed separately).
     * Defaults to {@link #maxResidencyMs()}; procedures with phases of very different legitimate durations
     * (e.g. CONNECT's lease wait, bounded by the adapter's discovery slice, vs its native-connect window)
     * override this so a slow-but-legitimate phase is not torn down by the fast phase's deadline.
     */
    default long maxResidencyMs(DeviceWaitingOn waitingOn) {
        return maxResidencyMs();
    }

    void start(DeviceProcedureContext ctx);

    void onEvent(DeviceEvent event, DeviceProcedureContext ctx);

    void cancel(String reason, DeviceProcedureContext ctx);
}
