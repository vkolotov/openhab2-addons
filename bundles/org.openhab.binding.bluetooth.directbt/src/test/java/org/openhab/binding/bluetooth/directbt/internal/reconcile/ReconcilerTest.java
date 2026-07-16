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
 * Regression harness for the reusable {@link Reconciler} base — the observe/inSync/act loop with backoff,
 * escalation and pause-freeze. These lock down the cross-cutting nasties that live in the base rather than in
 * a specific entity: a thrown {@code act()} must not silently cancel the loop; a paused reconciler must freeze
 * its timers (so a long prerequisite outage does not age it toward an escalation storm on recovery); and
 * corrective actions must be exponentially backed off.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
class ReconcilerTest {

    /** A minimal reconciler over a mutable boolean: desired = TRUE (in sync once observed == true). */
    private static final class TestReconciler extends Reconciler<Boolean, Boolean> {
        boolean observedValue;
        int actCalls;
        int escalateCalls;
        boolean actThrows;
        long escalateAfterMs = Long.MAX_VALUE;

        TestReconciler(MutableClock clock) {
            super("test", logger(), Boolean.TRUE, clock);
        }

        @Override
        protected Boolean observe() {
            return observedValue;
        }

        @Override
        protected boolean inSync(Boolean desired, Boolean observed) {
            return desired.equals(observed);
        }

        @Override
        protected void act(Boolean desired, Boolean observed) {
            actCalls++;
            if (actThrows) {
                throw new RuntimeException("simulated native failure in act()");
            }
        }

        @Override
        protected void escalate(Boolean desired, Boolean observed) {
            escalateCalls++;
        }

        @Override
        protected long escalateAfterMillis() {
            return escalateAfterMs;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // act()-throw guard: an act() that throws (e.g. a native exception) must be caught so the periodic
    // loop keeps running. Historically an unguarded throw could silently cancel scheduleWithFixedDelay and
    // wedge the whole reconciler until an openHAB restart (memory: reconcileTick silent-cancel).
    // ---------------------------------------------------------------------------------------------
    @Test
    void thrownActDoesNotAbortTheLoop() {
        MutableClock clock = new MutableClock(START);
        TestReconciler r = new TestReconciler(clock);
        r.observedValue = false; // out of sync -> act() runs
        r.actThrows = true;

        assertDoesNotThrow(r::reconcile, "a throwing act() must be caught, not propagated (would cancel the job)");
        assertEquals(1, r.actCalls);

        // The reconciler must still function on subsequent ticks after an act() threw.
        clock.advance(60_000); // clear any backoff
        r.actThrows = false;
        r.observedValue = true; // now in sync
        assertTrue(r.reconcile(), "reconciler keeps working after a prior act() threw");
    }

    // ---------------------------------------------------------------------------------------------
    // Exponential backoff: consecutive out-of-sync ticks must NOT act every tick; act is gated by a
    // growing backoff window (0.5s, 1s, 2s, ...). This is the anti-thrash that stopped the CSR reset storm.
    // ---------------------------------------------------------------------------------------------
    @Test
    void correctiveActionsAreBackedOff() {
        MutableClock clock = new MutableClock(START);
        TestReconciler r = new TestReconciler(clock);
        r.observedValue = false; // permanently out of sync

        r.reconcile(); // act #1 immediately
        assertEquals(1, r.actCalls);

        r.reconcile(); // same instant -> still backing off, no act
        assertEquals(1, r.actCalls, "must not act again within the backoff window");

        clock.advance(500); // first backoff = 500ms
        r.reconcile();
        assertEquals(2, r.actCalls, "after the backoff window elapses, act again");

        // Backoff has now grown to ~1000ms; 500ms is not enough for the next one.
        clock.advance(500);
        r.reconcile();
        assertEquals(2, r.actCalls, "backoff grows: 500ms is no longer enough after the second act");

        clock.advance(600);
        r.reconcile();
        assertEquals(3, r.actCalls);
    }

    // ---------------------------------------------------------------------------------------------
    // Escalation after a persistent delta.
    // ---------------------------------------------------------------------------------------------
    @Test
    void persistentDeltaEscalatesPastDeadline() {
        MutableClock clock = new MutableClock(START);
        TestReconciler r = new TestReconciler(clock);
        r.escalateAfterMs = 6000;
        r.observedValue = false;

        r.reconcile(); // out of sync since START; act but not yet escalate
        assertEquals(0, r.escalateCalls);

        clock.advance(6001);
        r.reconcile();
        assertEquals(1, r.escalateCalls, "a delta persisting past the escalate deadline escalates");
    }

    // ---------------------------------------------------------------------------------------------
    // Pause-freeze: while paused, reconcile() is a no-op AND timers are frozen — so a long prerequisite
    // outage does not age the entity toward its escalation deadline (which would cause a reset storm the
    // instant the prerequisite recovers). This is the load-bearing pause semantics from the design doc.
    // ---------------------------------------------------------------------------------------------
    @Test
    void pausedReconcilerDoesNotTickOrAge() {
        MutableClock clock = new MutableClock(START);
        TestReconciler r = new TestReconciler(clock);
        r.escalateAfterMs = 6000;
        r.observedValue = false;

        r.reconcile(); // out of sync since START
        assertEquals(1, r.actCalls);

        r.pause();
        assertFalse(r.reconcile(), "a paused reconciler does not tick");
        clock.advance(60_000); // a long prerequisite outage passes while paused
        assertFalse(r.reconcile());
        assertEquals(1, r.actCalls, "no act() while paused");
        assertEquals(0, r.escalateCalls, "the escalation deadline must be frozen while paused, not aged");

        // On recovery it resumes from where it froze — it must not immediately escalate as if 60s of delta passed.
        r.unpause();
        r.reconcile();
        assertEquals(0, r.escalateCalls, "on unpause it does not escalate for time spent paused");
    }

    @Test
    void inSyncClearsCountersAndReportsInSync() {
        MutableClock clock = new MutableClock(START);
        TestReconciler r = new TestReconciler(clock);
        r.observedValue = false;
        r.reconcile(); // act once
        assertFalse(r.isInSync());

        r.observedValue = true;
        assertTrue(r.reconcile());
        assertTrue(r.isInSync());
    }

    @Test
    void isPausedReflectsPauseState() {
        TestReconciler r = new TestReconciler(new MutableClock(START));
        assertFalse(r.isPaused());
        r.pause();
        assertTrue(r.isPaused());
        r.unpause();
        assertFalse(r.isPaused());
    }

    // ---------------------------------------------------------------------------------------------
    // Fresh-event expedite: backoff throttles repeated actions against an unchanged picture, but a
    // transport event is fresh evidence — expediteNextAct() must let the very next tick act instead of
    // sitting on the evidence for up to the backoff cap (the measured 8 s connected-but-unobserved gap).
    // ---------------------------------------------------------------------------------------------
    @Test
    void expediteNextActBypassesTheBackoff() {
        MutableClock clock = new MutableClock(START);
        TestReconciler r = new TestReconciler(clock);
        r.observedValue = false;

        for (int i = 0; i < 6; i++) {
            r.reconcile(); // repeated acts drive the backoff toward its cap
            clock.advance(200);
        }
        int actsBefore = r.actCalls;

        r.reconcile(); // deep inside the backoff window: must not act
        assertEquals(actsBefore, r.actCalls, "without an event the backoff window holds");

        r.expediteNextAct(); // a transport event delivered fresh evidence
        r.reconcile();
        assertEquals(actsBefore + 1, r.actCalls, "an expedited tick must act immediately on fresh evidence");
    }
}
