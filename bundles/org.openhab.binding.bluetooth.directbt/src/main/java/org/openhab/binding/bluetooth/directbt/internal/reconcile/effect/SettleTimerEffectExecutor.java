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

import java.util.function.LongSupplier;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.event.DeviceEffect;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.event.DeviceEffectOperation;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.event.DeviceEvent;

/**
 * Owns the actor-side link-settle timer effect.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
public final class SettleTimerEffectExecutor implements DeviceEffectExecutor {
    private final LongSupplier nowMillis;
    private final long settleDelayMs;

    private long generation = -1;
    private long dueAt = -1;

    public SettleTimerEffectExecutor(LongSupplier nowMillis, long settleDelayMs) {
        this.nowMillis = nowMillis;
        this.settleDelayMs = settleDelayMs;
    }

    @Override
    public boolean execute(DeviceEffect effect) {
        if (effect.operation() != DeviceEffectOperation.SCHEDULE_LINK_SETTLE_TIMER) {
            return false;
        }
        generation = effect.generation();
        dueAt = nowMillis.getAsLong() + settleDelayMs;
        return true;
    }

    @Nullable
    public DeviceEvent tick(long currentGeneration) {
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
