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
 * How a device procedure ended. Kept small and closed so it is safe as a metric label.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
public enum DeviceProcedureOutcome {
    /** Handed off to the next procedure in the sequence — the healthy path. */
    HANDED_OFF,
    /** Reached its terminal success state. */
    SUCCEEDED,
    /** Ended on a failure event (connect failed, GATT resolve failed). */
    FAILED,
    /** Exceeded its residency deadline: it never finished either way. */
    DEADLINE_EXPIRED,
    /** Cancelled because the device is no longer wanted, or replaced by another procedure. */
    CANCELLED,
    /** Abandoned because an adapter reset invalidated every native handle. */
    ADAPTER_RESET
}
