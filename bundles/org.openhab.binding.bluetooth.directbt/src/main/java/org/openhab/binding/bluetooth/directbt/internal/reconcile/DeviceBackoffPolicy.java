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

/**
 * Applies once-per-generation port cleanup when an actor procedure reaches BACKING_OFF.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
final class DeviceBackoffPolicy {
    private final DevicePort port;
    private long appliedGeneration = -1;

    DeviceBackoffPolicy(DevicePort port) {
        this.port = port;
    }

    boolean apply(DeviceActorDiagnostics diagnostics) {
        if (diagnostics.state() != DeviceActorState.BACKING_OFF
                || diagnostics.generation() == appliedGeneration) {
            return false;
        }
        if (port.hasStalePairing()) {
            port.clearStalePairing();
        }
        port.markDisconnected();
        appliedGeneration = diagnostics.generation();
        return true;
    }
}
