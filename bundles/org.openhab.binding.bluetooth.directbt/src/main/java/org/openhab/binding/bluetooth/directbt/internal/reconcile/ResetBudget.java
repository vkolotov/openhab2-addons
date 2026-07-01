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
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.slf4j.Logger;

/**
 * A shared minimum-interval gate for adapter {@code reset()}, used by both the adapter and discovery
 * reconcilers. A bare HCI reset clears the controller but un-powers it transiently and disrupts every
 * connection, so two reconcilers resetting within the cooldown can chase each other into an un-powered
 * loop (observed live on the CSR as a 10s reset storm). All resets funnel through {@link #tryReset(String)}.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
public class ResetBudget {

    private static final long DEFAULT_MIN_INTERVAL_MS = TimeUnit.SECONDS.toMillis(8);

    private final Logger logger;
    private final long minIntervalMs;
    private final Clock clock;
    private long nextResetNotBefore;

    public ResetBudget(Logger logger) {
        this(logger, DEFAULT_MIN_INTERVAL_MS);
    }

    public ResetBudget(Logger logger, long minIntervalMs) {
        this(logger, minIntervalMs, Clock.systemUTC());
    }

    public ResetBudget(Logger logger, long minIntervalMs, Clock clock) {
        this.logger = logger;
        this.minIntervalMs = minIntervalMs;
        this.clock = clock;
    }

    /** @return true if a reset is permitted now (and consumes the budget); false if still in cooldown. */
    public synchronized boolean tryReset(String requester) {
        long now = clock.millis();
        if (now < nextResetNotBefore) {
            logger.debug("[reconcile] reset requested by {} but rate-limited ({}ms left)", requester,
                    nextResetNotBefore - now);
            return false;
        }
        nextResetNotBefore = now + minIntervalMs;
        return true;
    }
}
