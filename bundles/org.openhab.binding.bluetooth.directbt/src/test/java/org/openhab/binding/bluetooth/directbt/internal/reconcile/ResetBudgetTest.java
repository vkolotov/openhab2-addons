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

import static org.junit.jupiter.api.Assertions.*;
import static org.openhab.binding.bluetooth.directbt.internal.reconcile.ReconcileTestSupport.*;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;

/**
 * Regression harness for {@link ResetBudget} — the shared minimum-interval gate for adapter {@code reset()}.
 * <p>
 * A bare HCI reset transiently un-powers the controller and disrupts every connection, so two reconcilers
 * resetting within the cooldown can chase each other into an un-powered loop (observed live on the CSR as a
 * ~10s reset storm). The budget must permit a reset only once per cooldown regardless of how many requesters
 * ask. Cross-reference: {@code docs/directbt-reconciler-design.md} (§"Reusable mechanism", reset budget).
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
class ResetBudgetTest {

    private static final long COOLDOWN_MS = BUDGET_COOLDOWN_MS;

    @Test
    void secondResetWithinCooldownIsDeniedEvenFromAnotherRequester() {
        MutableClock clock = new MutableClock(START);
        ResetBudget budget = new ResetBudget(logger(), COOLDOWN_MS, clock);

        assertTrue(budget.tryReset("adapter"), "first reset is permitted");
        assertFalse(budget.tryReset("device"), "a second reset within the cooldown (the storm) is denied");
        assertFalse(budget.tryReset("discovery"), "any requester is gated by the same shared budget");
    }

    @Test
    void resetPermittedAgainAfterCooldownElapses() {
        MutableClock clock = new MutableClock(START);
        ResetBudget budget = new ResetBudget(logger(), COOLDOWN_MS, clock);

        assertTrue(budget.tryReset("adapter"));

        clock.advance(COOLDOWN_MS - 1);
        assertFalse(budget.tryReset("adapter"), "still within cooldown");

        clock.advance(2); // now just past the cooldown
        assertTrue(budget.tryReset("adapter"), "after the cooldown a fresh reset is permitted");
    }
}
