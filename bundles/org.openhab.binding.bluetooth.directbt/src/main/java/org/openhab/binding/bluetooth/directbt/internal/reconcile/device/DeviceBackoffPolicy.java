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
import org.openhab.binding.bluetooth.directbt.internal.reconcile.port.DevicePort;

/**
 * Applies once-per-generation port cleanup when an actor procedure reaches BACKING_OFF.
 * <p>
 * Deliberately NOT the place stale bonds are cleared: BACKING_OFF is reached from every teardown (connect
 * failures, but also resolve/settle/subscribe deadlines on an established link, which say nothing about the
 * keys). Bond clearing is evidence-driven (frozen constraint 9): the CONNECT procedure emits the clear effect
 * on failed/timed-out connect attempts, and the effect executor gates it on {@link DevicePort#hasStalePairing()}.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
public final class DeviceBackoffPolicy {
    private final DevicePort port;
    private long appliedGeneration = -1;

    public DeviceBackoffPolicy(DevicePort port) {
        this.port = port;
    }

    public boolean apply(DeviceActorDiagnostics diagnostics) {
        if (diagnostics.state() != DeviceActorState.BACKING_OFF || diagnostics.generation() == appliedGeneration) {
            return false;
        }
        if (diagnostics.waitingOn() == DeviceWaitingOn.ADAPTER_RESET) {
            port.markDisconnectedByAdapterReset();
        } else {
            port.markDisconnected();
        }
        appliedGeneration = diagnostics.generation();
        return true;
    }
}
