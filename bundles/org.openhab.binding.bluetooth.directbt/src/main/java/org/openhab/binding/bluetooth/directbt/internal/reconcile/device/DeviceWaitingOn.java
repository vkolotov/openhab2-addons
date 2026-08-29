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

/**
 * Coarse "what are we blocked on?" diagnostic for silent-wait debugging.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
public enum DeviceWaitingOn {
    NOTHING,
    NATIVE_HANDLE,
    CONNECT_LEASE,
    NATIVE_CONNECT,
    PAIRING,
    SETTLE_TIMER,
    GATT_RESOLVE,
    SUBSCRIPTION,
    BACKOFF_TIMER,
    DISCONNECT,
    ADAPTER_RESET
}
