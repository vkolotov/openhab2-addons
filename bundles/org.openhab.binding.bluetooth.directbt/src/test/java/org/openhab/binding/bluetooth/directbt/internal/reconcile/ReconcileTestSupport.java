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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared fixtures for the reconcile-package tests: a common start epoch, a logger, and a rate-limit budget
 * factory. Centralises what was duplicated across the individual test classes.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
final class ReconcileTestSupport {

    /** Arbitrary non-zero epoch the tests advance from (reconcilers treat 0 as "unset"). */
    static final long START = 1_000_000L;

    /** Default rate-limit cooldown used by most tests. */
    static final long BUDGET_COOLDOWN_MS = 8000;

    private ReconcileTestSupport() {
    }

    static Logger logger() {
        return LoggerFactory.getLogger("directbt-reconcile-test");
    }

    /** A reset budget on the given clock with the default cooldown. */
    static ResetBudget budget(MutableClock clock) {
        return new ResetBudget(BUDGET_COOLDOWN_MS, clock);
    }
}
