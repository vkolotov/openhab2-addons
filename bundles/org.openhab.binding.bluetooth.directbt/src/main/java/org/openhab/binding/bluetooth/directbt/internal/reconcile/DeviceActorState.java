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
 * Procedure-level device lifecycle state exposed by the actor diagnostics.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
enum DeviceActorState {
    IDLE_DISABLED,
    DISCOVERING,
    CONNECTING,
    LINK_SETTLING,
    RESOLVING_GATT,
    SUBSCRIBING,
    ONLINE,
    RECOVERING,
    DISCONNECTING,
    BACKING_OFF
}
