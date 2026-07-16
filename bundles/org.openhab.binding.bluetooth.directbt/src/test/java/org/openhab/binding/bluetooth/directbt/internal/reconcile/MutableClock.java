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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * A hand-advanced {@link Clock} for deterministic timing tests: no sleeps, no wall-clock flakiness. Tests
 * {@link #advance(long)} it to cross the reconciler's deadlines / backoff windows exactly.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
public class MutableClock extends Clock {

    private long millis;

    public MutableClock(long startMillis) {
        this.millis = startMillis;
    }

    /** Move time forward by {@code deltaMillis} (must be >= 0). */
    public void advance(long deltaMillis) {
        millis += deltaMillis;
    }

    @Override
    public long millis() {
        return millis;
    }

    @Override
    public Instant instant() {
        return Instant.ofEpochMilli(millis);
    }

    @Override
    public ZoneId getZone() {
        return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(@Nullable ZoneId zone) {
        return this; // single-zone test clock; zone is irrelevant to millis-based reconcilers
    }
}
