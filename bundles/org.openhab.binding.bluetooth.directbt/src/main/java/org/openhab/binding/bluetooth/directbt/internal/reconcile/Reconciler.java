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
    /** Time source; {@link Clock#systemUTC()} in production, a mutable clock in tests. */
    protected final Clock clock;

    /** Intent, fixed at construction. Entities whose intent varies derive it inside {@link #inSync}/{@link #act}. */
    protected final D desired;

    /** Last polled native truth (recomputed every tick). */
    protected @Nullable O observed;

    private boolean paused;
    private long pausedAt;

    // Bookkeeping (all in epoch millis unless noted). Advanced by the paused duration on unpause so time spent
    // paused does not age these deadlines (see pause/unpause).
    private long inSyncSince;
    private long outOfSyncSince;
    private long nextActNotBefore;
    private int consecutiveActs;

    protected Reconciler(String name, Logger logger, D initialDesired) {
        this(name, logger, initialDesired, Clock.systemUTC());
    }

    protected Reconciler(String name, Logger logger, D initialDesired, Clock clock) {
        this.name = name;
        this.logger = logger;
        this.desired = initialDesired;
        this.clock = clock;
    }

    // --- abstract contract -----------------------------------------------------------------------

    /** Poll the current native truth. Must not mutate state; pure observation. */
    protected abstract O observe();

    /** @return true if the observed state already satisfies the desired intent. */
    protected abstract boolean inSync(D desired, O observed);

    /** Optional observation-side hook for diagnostics. Must not issue native/controller commands. */
    protected void afterObserve(D desired, O observed) {
    }

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
     * Fresh native evidence arrived (a transport event): allow the next act immediately instead of waiting out
     * the exponential backoff. The backoff throttles repeated corrective actions against an unchanged picture;
     * an event changes the picture, and refusing to LOOK at it for up to the backoff cap is not throttling
     * (measured as an ~8 s blind spot between an established connection and the reconciler observing it).
     * Acting stays naturally rate-limited: one requeued tick per event, and the command paths keep their own
     * retry spacing (e.g. the connect retry interval).
     */
    public final void expediteNextAct() {
        nextActNotBefore = 0;
    }

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
        long now = clock.millis();
        D des = desired;
        afterObserve(des, obs);
        if (inSync(des, obs)) {
            if (inSyncSince == 0) {
                inSyncSince = now;
                logger.debug("[reconcile:{}] in sync", name);
            }
            consecutiveActs = 0;
            outOfSyncSince = 0;
            nextActNotBefore = 0;
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
            logger.debug("[reconcile:{}] act threw", name, e);
        }
        consecutiveActs++;
        nextActNotBefore = now + backoffMillis(consecutiveActs);
        if (now - outOfSyncSince > escalateAfterMillis()) {
            try {
                logger.warn("[reconcile:{}] delta persisted {}ms; escalating", name, now - outOfSyncSince);
                escalate(des, obs);
            } catch (RuntimeException e) {
                logger.debug("[reconcile:{}] escalate threw", name, e);
            }
        }
        return false;
    }

    // --- pause/freeze (dependency gating) --------------------------------------------------------

    /** Pause this reconciler: it will not tick and its timers are frozen until {@link #unpause()}. */
    public final void pause() {
        if (!paused) {
            paused = true;
            pausedAt = clock.millis();
            logger.debug("[reconcile:{}] paused (prerequisite not in sync)", name);
        }
    }

    /**
     * Resume ticking. The bookkeeping timestamps are epoch-based, so to make elapsed-while-paused "not count"
     * (the load-bearing freeze semantics — a long prerequisite outage must NOT age this entity toward its
     * escalation deadline and trigger a reset storm on recovery) we shift every live deadline forward by the
     * paused duration. Effect: on the first tick after unpause, {@code now - outOfSyncSince} equals what it was
     * at pause time, so escalation/backoff resume exactly where they froze.
     */
    public final void unpause() {
        if (paused) {
            paused = false;
            long pausedFor = clock.millis() - pausedAt;
            if (pausedFor > 0) {
                if (inSyncSince != 0) {
                    inSyncSince += pausedFor;
                }
                if (outOfSyncSince != 0) {
                    outOfSyncSince += pausedFor;
                }
                if (nextActNotBefore != 0) {
                    nextActNotBefore += pausedFor;
                }
            }
            logger.debug("[reconcile:{}] unpaused (frozen {}ms)", name, pausedFor);
        }
    }

    public final boolean isPaused() {
        return paused;
    }

    /** @return true if the last completed tick found the entity in-sync (and it is not paused). */
    public final boolean isInSync() {
        return !paused && inSyncSince != 0;
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
