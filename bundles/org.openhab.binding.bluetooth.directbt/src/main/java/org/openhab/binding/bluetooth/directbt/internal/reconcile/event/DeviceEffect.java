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
package org.openhab.binding.bluetooth.directbt.internal.reconcile.event;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Effect emitted by a procedure. The actor records effects; blocking native code is executed elsewhere and reports
 * back with generation-tagged events.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
public final class DeviceEffect {
    private final long generation;
    private final DeviceEffectOperation operation;

    public DeviceEffect(long generation, DeviceEffectOperation operation) {
        this.generation = generation;
        this.operation = operation;
    }

    public long generation() {
        return generation;
    }

    public DeviceEffectOperation operation() {
        return operation;
    }
}
