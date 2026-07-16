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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;

/**
 * Tests for the link-settle timer effect executor.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
class SettleTimerEffectExecutorTest {
    private static final long START = 1_784_200_000_000L;
    private static final long GENERATION = 3;

    @Test
    void scheduledTimerExpiresAfterDelay() {
        MutableClock clock = new MutableClock(START);
        SettleTimerEffectExecutor executor = new SettleTimerEffectExecutor(() -> clock.millis(), 2_000);

        assertTrue(
                executor.execute(new DeviceEffect(GENERATION, SettleLinkProcedure.EFFECT_SCHEDULE_LINK_SETTLE_TIMER)));
        assertNull(executor.tick(GENERATION));

        clock.advance(2_000);

        assertTrue(executor.tick(GENERATION) instanceof DeviceEvent.LinkSettleTimerExpired);
        assertNull(executor.tick(GENERATION));
    }

    @Test
    void staleGenerationTimerIsDropped() {
        MutableClock clock = new MutableClock(START);
        SettleTimerEffectExecutor executor = new SettleTimerEffectExecutor(() -> clock.millis(), 2_000);

        assertTrue(
                executor.execute(new DeviceEffect(GENERATION, SettleLinkProcedure.EFFECT_SCHEDULE_LINK_SETTLE_TIMER)));
        clock.advance(2_000);

        assertNull(executor.tick(GENERATION + 1));
        assertNull(executor.tick(GENERATION));
    }

    @Test
    void unknownEffectPassesThrough() {
        MutableClock clock = new MutableClock(START);
        SettleTimerEffectExecutor executor = new SettleTimerEffectExecutor(() -> clock.millis(), 2_000);

        assertFalse(executor.execute(new DeviceEffect(GENERATION, "other")));
    }
}
