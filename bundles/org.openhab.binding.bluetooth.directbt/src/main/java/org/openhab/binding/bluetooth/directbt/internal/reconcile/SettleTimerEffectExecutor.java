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

import java.util.function.LongSupplier;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * Owns the actor-side link-settle timer effect.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
final class SettleTimerEffectExecutor implements DeviceEffectExecutor {
    private final LongSupplier nowMillis;
    private final long settleDelayMs;

    private long generation = -1;
    private long dueAt = -1;

    SettleTimerEffectExecutor(LongSupplier nowMillis, long settleDelayMs) {
        this.nowMillis = nowMillis;
        this.settleDelayMs = settleDelayMs;
    }

    @Override
    public boolean execute(DeviceEffect effect) {
        if (!SettleLinkProcedure.EFFECT_SCHEDULE_LINK_SETTLE_TIMER.equals(effect.operation())) {
            return false;
        }
        generation = effect.generation();
        dueAt = nowMillis.getAsLong() + settleDelayMs;
        return true;
    }

    @Nullable
    DeviceEvent tick(long currentGeneration) {
        if (dueAt < 0) {
            return null;
        }
        if (generation != currentGeneration) {
            clear();
            return null;
        }
        if (nowMillis.getAsLong() < dueAt) {
            return null;
        }
        long expiredGeneration = generation;
        clear();
        return new DeviceEvent.LinkSettleTimerExpired(expiredGeneration);
    }

    private void clear() {
        generation = -1;
        dueAt = -1;
    }
}
