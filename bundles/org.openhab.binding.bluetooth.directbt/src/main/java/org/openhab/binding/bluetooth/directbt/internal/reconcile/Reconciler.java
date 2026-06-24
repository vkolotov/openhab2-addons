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
import org.eclipse.jdt.annotation.Nullable;
import org.slf4j.Logger;

/**
 * A reusable level-triggered reconciler for a single entity whose backing stack is eventually consistent.
 * <p>
 * Direct-BT commands are "accepted, not done" and its status events are hints that may be dropped, so this
 * does not sequence commands off return-codes or events. Instead each {@link #reconcile()} tick:
 * <ol>
 * <li>{@link #observe() polls the native truth} (never a remembered flag or a prior command's return code),</li>
 * <li>compares it to the {@link #desired} intent via {@link #inSync(Object, Object)},</li>
 * <li>and, only on a delta, issues the idempotent corrective {@link #act(Object, Object)} command.</li>
 * </ol>
 * Convergence is verified on the <em>next</em> tick (the command is never trusted). A persistent delta past
 * {@link #escalateAfterMillis()} triggers {@link #escalate(Object, Object)} (a harder corrective action).
 * Corrective actions are exponentially backed off (per entity) so a stuck entity cannot thrash the controller.
 * <p>
 * A reconciler may be {@link #pause() paused} when a prerequisite reconciler is not in-sync; while paused it is
 * not ticked and all its timers are <em>frozen</em> (not advanced), so a long prerequisite outage does not age
 * this entity toward its escalation deadline (which would cause a recovery-time escalation storm).
 *
 * @param <D> the desired-state type (intent)
 * @param <O> the observed-state type (polled native truth)
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
public abstract class Reconciler<D, O> {

    /** Default exponential-backoff base and cap for corrective actions (milliseconds). */
    private static final long BACKOFF_BASE_MS = 500;
    private static final long BACKOFF_CAP_MS = 8000;

    protected final String name;
    protected final Logger logger;

    /** Intent. Volatile because event/caller threads set it while the driver thread reads it. */
    protected volatile D desired;

    /** Last polled native truth (recomputed every tick). */
    protected @Nullable O observed;

    private boolean paused;

    // Bookkeeping (all in epoch millis unless noted). Frozen while paused.
    private long lastObservedAt;
    private long lastActAt;
    private long inSyncSince;
    private long outOfSyncSince;
    private long nextActNotBefore;
    private int consecutiveActs;
    private @Nullable String lastError;

    protected Reconciler(String name, Logger logger, D initialDesired) {
        this.name = name;
        this.logger = logger;
        this.desired = initialDesired;
    }

    // --- abstract contract -----------------------------------------------------------------------

    /** Poll the current native truth. Must not mutate state; pure observation. */
    protected abstract O observe();

    /** @return true if the observed state already satisfies the desired intent. */
    protected abstract boolean inSync(D desired, O observed);

    /** Issue the idempotent corrective command to move observed toward desired. */
    protected abstract void act(D desired, O observed);

    /** Optional harder corrective action when the delta has persisted past {@link #escalateAfterMillis()}. */
    protected void escalate(D desired, O observed) {
    }

    /** How long a delta may persist before {@link #escalate} is invoked. {@link Long#MAX_VALUE} disables it. */
    protected long escalateAfterMillis() {
        return Long.MAX_VALUE;
    }

    // --- driver entry point ----------------------------------------------------------------------

    /**
     * Run one reconcile tick. Returns true if the entity is currently in-sync (so dependents may run).
     * No-op (and returns the last known in-sync verdict as false) while paused.
     */
    public final boolean reconcile() {
        if (paused) {
            return false;
        }
        O obs = observe();
        this.observed = obs;
        long now = System.currentTimeMillis();
        this.lastObservedAt = now;
        D des = desired;
        if (inSync(des, obs)) {
            if (inSyncSince == 0) {
                inSyncSince = now;
                logger.debug("[reconcile:{}] in sync", name);
            }
            consecutiveActs = 0;
            outOfSyncSince = 0;
            nextActNotBefore = 0;
            lastError = null;
            return true;
        }
        inSyncSince = 0;
        if (outOfSyncSince == 0) {
            outOfSyncSince = now;
        }
        if (now < nextActNotBefore) {
            return false; // backing off
        }
        try {
            act(des, obs);
        } catch (RuntimeException e) {
            lastError = e.getMessage();
            logger.debug("[reconcile:{}] act threw", name, e);
        }
        lastActAt = now;
        consecutiveActs++;
        nextActNotBefore = now + backoffMillis(consecutiveActs);
        if (now - outOfSyncSince > escalateAfterMillis()) {
            try {
                logger.warn("[reconcile:{}] delta persisted {}ms; escalating", name, now - outOfSyncSince);
                escalate(des, obs);
            } catch (RuntimeException e) {
                lastError = e.getMessage();
                logger.debug("[reconcile:{}] escalate threw", name, e);
            }
        }
        return false;
    }

    /** Run a reconcile tick now, on the calling (driver) thread. Convenience for event-driven requeue. */
    public final void requeue() {
        reconcile();
    }

    // --- pause/freeze (dependency gating) --------------------------------------------------------

    /** Pause this reconciler: it will not tick and its timers are frozen until {@link #unpause()}. */
    public final void pause() {
        if (!paused) {
            paused = true;
            logger.debug("[reconcile:{}] paused (prerequisite not in sync)", name);
        }
    }

    /** Resume ticking; timers resume from where they were frozen (no catch-up). */
    public final void unpause() {
        if (paused) {
            paused = false;
            logger.debug("[reconcile:{}] unpaused", name);
        }
    }

    public final boolean isPaused() {
        return paused;
    }

    /** @return true if the last completed tick found the entity in-sync (and it is not paused). */
    public final boolean isInSync() {
        return !paused && inSyncSince != 0;
    }

    public final void setDesired(D newDesired) {
        this.desired = newDesired;
    }

    public final @Nullable O getObserved() {
        return observed;
    }

    public final @Nullable String getLastError() {
        return lastError;
    }

    /** Exponential backoff with a cap: BASE * 2^(n-1), capped. */
    private static long backoffMillis(int consecutiveActs) {
        int n = Math.max(1, consecutiveActs);
        long ms = BACKOFF_BASE_MS;
        for (int i = 1; i < n && ms < BACKOFF_CAP_MS; i++) {
            ms <<= 1;
        }
        return Math.min(ms, BACKOFF_CAP_MS);
    }
}
