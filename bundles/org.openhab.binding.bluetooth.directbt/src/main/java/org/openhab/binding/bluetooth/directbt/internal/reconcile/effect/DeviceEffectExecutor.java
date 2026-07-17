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
package org.openhab.binding.bluetooth.directbt.internal.reconcile.effect;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.event.DeviceEffect;

/**
 * Boundary for executing actor-emitted side effects outside the actor thread.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
public interface DeviceEffectExecutor {
    /**
     * Executes a known effect.
     *
     * @return true when the effect was understood by this executor.
     */
    boolean execute(DeviceEffect effect);
}
