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

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import org.direct_bt.HCIStatusCode;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;

/**
 * The implementation-neutral behavioural contract for the device connection lifecycle. Every test here
 * encodes a field failure from production (see docs/directbt-device-fsm-actor-proposal-2026-07-16.md and the
 * 2026-07 incident record in docs/directbt-heatpump-recovery-fix-plan-2026-07-15.md) as EFFECTS on the
 * {@link FakeDevicePort} within timing WINDOWS — never internal mechanics — so the same suite must pass
 * against the current {@link DeviceReconciler} and the future actor/procedure implementation.
 *
 * Conventions:
 * <ul>
 * <li>Time is virtual; {@link #runFor}/{@link #runUntil} step the clock in {@link #TICK_STEP_MS} increments
 * and give the implementation one convergence opportunity per step (the "periodic tick" model).</li>
 * <li>Windows are deliberately generous: the contract forbids "never" and "instantly-wrong", not exact
 * cadences. Exact constants (backoff curves, retry spacing values) are implementation tests, not contract
 * tests.</li>
 * </ul>
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
abstract class DeviceLifecycleContract {

    /** The contract's periodic-tick granularity (production ticks are coarser; windows account for that). */
    private static final long TICK_STEP_MS = 500;

    // JUnit constructs a fresh test instance per method, so every test gets a fresh fixture. The factory is
    // invoked during construction; implementations must not rely on subclass instance state.
    private final DeviceLifecycleFixture fx = newFixture();

    /** @return a fresh fixture around a fresh implementation instance. */
    protected abstract DeviceLifecycleFixture newFixture();

    // --- helpers ----------------------------------------------------------------------------------

    /** Step time and tick for the given duration. */
    private void runFor(long ms) {
        for (long t = 0; t < ms; t += TICK_STEP_MS) {
            fx.advance(TICK_STEP_MS);
            fx.tick();
        }
    }

    /** Step time and tick until the condition holds. @return elapsed ms, or -1 if maxMs passed first. */
    private long runUntil(BooleanSupplier condition, long maxMs) {
        for (long t = 0; t < maxMs; t += TICK_STEP_MS) {
            if (condition.getAsBoolean()) {
                return t;
            }
            fx.advance(TICK_STEP_MS);
            fx.tick();
        }
        return condition.getAsBoolean() ? maxMs : -1;
    }

    /** A wanted device holding a native handle, adapter healthy, scan off — ready to connect. */
    private FakeDevicePort connectReady() {
        FakeDevicePort port = fx.port();
        port.wanted = true;
        port.hasNative = true;
        port.connectResult = HCIStatusCode.SUCCESS;
        return port;
    }

    /** Drive to an established, GATT-resolved steady state. */
    private void driveOnline() {
        FakeDevicePort port = connectReady();
        assertTrue(runUntil(() -> port.connectNativeCalls >= 1, 10_000) >= 0, "connect must be attempted");
        port.settleNativeConnected();
        fx.fireConnectedEvent();
        fx.tick();
        assertTrue(runUntil(() -> port.gattResolved, 30_000) >= 0, "device must reach resolved GATT");
    }

    // --- radio demands (what the device asks of the adapter) ---------------------------------------

    @Test
    void wantedDeviceWithoutHandleWantsDiscovery() {
        fx.port().wanted = true;
        fx.port().hasNative = false;
        assertTrue(fx.wantsDiscovery(), "cold start: only the scan can produce the handle");
        assertFalse(fx.needsConnectWindow(), "no handle -> discovery, not a connect window");
    }

    @Test
    void wantedDeviceWithHandleWantsConnectWindowNotDiscovery() {
        fx.port().wanted = true;
        fx.port().hasNative = true;
        assertFalse(fx.wantsDiscovery(), "holding a handle: scanning only blocks the connect");
        assertTrue(fx.needsConnectWindow());
    }

    @Test
    void unwantedDeviceAsksForNothing() {
        fx.port().wanted = false;
        assertFalse(fx.wantsDiscovery());
        assertFalse(fx.needsConnectWindow());
    }

    // --- connect gating -----------------------------------------------------------------------------

    @Test
    void connectIsIssuedPromptlyWhenScanIsOff() {
        FakeDevicePort port = connectReady();
        long elapsed = runUntil(() -> port.connectNativeCalls >= 1, 10_000);
        assertTrue(elapsed >= 0 && elapsed <= 10_000, "a connectable wanted device must be attempted promptly");
    }

    @Test
    void noConnectWhileScanIsActive_thenIssuedOnceScanStops() {
        FakeDevicePort port = connectReady();
        fx.setScanActive(true);
        runFor(15_000);
        assertEquals(0, port.connectNativeCalls, "the controller rejects create-connection during a scan; "
                + "the implementation must not issue one (2026-07-16 COMMAND_DISALLOWED races)");

        fx.setScanActive(false);
        assertTrue(runUntil(() -> port.connectNativeCalls >= 1, 10_000) >= 0,
                "once the scan stops the pending connect must fire");
    }

    @Test
    void connectAttemptsAreRetriedButNeverHammered() {
        FakeDevicePort port = connectReady(); // accepted but never establishes (no settle)
        List<Long> attemptTimes = new ArrayList<>();
        int seen = 0;
        for (long t = 0; t < 90_000; t += TICK_STEP_MS) {
            fx.advance(TICK_STEP_MS);
            fx.port().hasNative = true; // the device stays discoverable; re-found after every teardown
            fx.tick();
            if (port.connectNativeCalls > seen) {
                seen = port.connectNativeCalls;
                attemptTimes.add(fx.nowMillis());
            }
        }
        assertTrue(attemptTimes.size() >= 3, "an unreachable device must keep being retried, got "
                + attemptTimes.size());
        assertTrue(attemptTimes.size() <= 60, "retries must be paced, not hammered every tick");
        for (int i = 1; i < attemptTimes.size(); i++) {
            assertTrue(attemptTimes.get(i) - attemptTimes.get(i - 1) >= 1000,
                    "attempts must be spaced by at least a second");
        }
    }

    // --- evidence handling (the 2026-07-16 event-driven fixes) --------------------------------------

    @Test
    void disconnectEventDuringConnectingClearsTheAttemptFast() {
        FakeDevicePort port = connectReady();
        assertTrue(runUntil(() -> port.connectNativeCalls >= 1, 10_000) >= 0);

        fx.advance(300); // the 0x3e establishment failure arrives ~300ms after the attempt
        fx.fireDisconnectedEvent();
        fx.tick();
        assertTrue(port.markDisconnectedCalls >= 1,
                "a reported-failed attempt must be cleared on the event, not after a multi-second deadline");
    }

    @Test
    void connectionIsObservedPromptlyEvenAfterARetryStorm() {
        // Regression for the 8s connected-but-unobserved blind spot: repeated failures inflate internal
        // backoff, then the link finally establishes — the implementation must notice within ~1s of the event.
        FakeDevicePort port = connectReady();
        for (int i = 0; i < 3; i++) {
            final int target = port.connectNativeCalls + 1;
            assertTrue(runUntil(() -> port.connectNativeCalls >= target, 30_000) >= 0);
            fx.advance(300);
            fx.fireDisconnectedEvent();
            fx.tick();
            fx.fireHandleFoundEvent(); // re-discovered after the teardown
            fx.tick();
        }
        final int target = port.connectNativeCalls + 1;
        assertTrue(runUntil(() -> port.connectNativeCalls >= target, 30_000) >= 0);
        port.settleNativeConnected();
        fx.fireConnectedEvent();
        fx.tick();
        assertTrue(port.flagConnected, "fresh evidence must preempt any wait: the established link must be "
                + "observed on the event, not after the backoff expires (frozen constraint 11)");
    }

    @Test
    void pairingChurnIsNotTreatedAsFailure() {
        FakeDevicePort port = connectReady();
        assertTrue(runUntil(() -> port.connectNativeCalls >= 1, 10_000) >= 0);

        port.pairing = true; // SMP ladder begins: its connect/disconnect churn is progress, not failure
        fx.fireDisconnectedEvent();
        fx.tick();
        assertEquals(0, port.markDisconnectedCalls, "pairing churn must not tear the negotiation down");

        runFor(5_000);
        port.pairing = false;
        fx.tick();
        assertEquals(0, port.markDisconnectedCalls, "a churn event must not fast-fail the attempt after "
                + "pairing ends (the deadline was frozen during SMP)");
    }

    // --- fresh-link GATT discipline (settle delay + warm-up, 2026-07-16) ----------------------------

    @Test
    void gattIsNotProbedTheInstantTheLinkComesUp() {
        FakeDevicePort port = connectReady();
        assertTrue(runUntil(() -> port.connectNativeCalls >= 1, 10_000) >= 0);
        port.settleNativeConnected();
        fx.fireConnectedEvent();
        fx.tick();
        assertEquals(0, port.resolveGattCalls,
                "the ATT path is not usable the moment the ACL reports connected; probing it returned "
                        + "instant-empty models and coincided with fast 0x3e drops");

        assertTrue(runUntil(() -> port.resolveGattCalls >= 1, 15_000) >= 0,
                "after the settle window GATT discovery must proceed");
        assertTrue(runUntil(() -> port.gattResolved, 15_000) >= 0);
    }

    @Test
    void instantEmptyResolvesOnAFreshLinkAreNotFatal() {
        FakeDevicePort port = connectReady();
        port.resolveSucceeds = false; // native GATT stays unservable
        assertTrue(runUntil(() -> port.connectNativeCalls >= 1, 10_000) >= 0);
        port.settleNativeConnected();
        fx.fireConnectedEvent();
        fx.tick();

        runFor(3_000);
        assertEquals(0, port.markDisconnectedCalls,
                "instant empty resolves during link warm-up are 'not ready yet', not a dead link");

        assertTrue(runUntil(() -> port.markDisconnectedCalls >= 1, 120_000) >= 0,
                "a link whose GATT never becomes servable must eventually be torn down and rebuilt");
    }

    @Test
    void inFlightGattDiscoveryIsProgressButNotForever() {
        FakeDevicePort port = fx.port();
        port.wanted = true;
        port.hasNative = true;
        port.nativeConnected = true;
        port.flagConnected = true;
        port.gattResolved = false;
        port.gattResolving = true; // a discovery that never returns (hung native walk)

        runFor(60_000);
        assertEquals(0, port.markDisconnectedCalls,
                "an in-flight service walk must never be torn down mid-flight (the 12:00 race lesson)");

        assertTrue(runUntil(() -> port.markDisconnectedCalls >= 1, 180_000) >= 0,
                "but a discovery in flight beyond any legitimate duration is hung and must not suppress "
                        + "recovery forever (frozen constraint 5)");
    }

    // --- silent drops and steady state ---------------------------------------------------------------

    @Test
    void silentNativeDropIsDetectedWithoutAnyEvent() {
        driveOnline();
        FakeDevicePort port = fx.port();
        int before = port.markDisconnectedCalls;

        port.silentDrop(); // native link gone; NO deviceDisconnected is ever delivered
        assertTrue(runUntil(() -> port.markDisconnectedCalls > before, 30_000) >= 0,
                "polling native truth is the only defence against dropped events (THE defining failure)");
        assertFalse(port.hasNative, "the stale handle must be cleared so the device is re-found fresh");
    }

    @Test
    void unwantedConnectedDeviceIsDisconnected() {
        FakeDevicePort port = fx.port();
        port.wanted = false;
        port.hasNative = true;
        port.nativeConnected = true;
        port.flagConnected = true;
        port.gattResolved = true;

        assertTrue(runUntil(() -> port.disconnectNativeCalls >= 1 && port.markDisconnectedCalls >= 1, 10_000) >= 0,
                "an unwanted device must not hold the ACL open");
    }

    // --- escalation discipline ------------------------------------------------------------------------

    @Test
    void connectRejectionDoesNotResetTheAdapterImmediately() {
        FakeDevicePort port = connectReady();
        port.connectResult = HCIStatusCode.COMMAND_DISALLOWED;
        assertTrue(runUntil(() -> port.connectNativeCalls >= 1, 10_000) >= 0);
        assertEquals(0, fx.adapterResetRequests(),
                "one rejection is the benign connect/scan race, not a wedged controller; the adapter reset "
                        + "is a dangerous native call and a last resort");
    }

    @Test
    void persistentConnectRejectionEscalatesToOneAdapterReset() {
        FakeDevicePort port = connectReady();
        port.connectResult = HCIStatusCode.COMMAND_DISALLOWED;
        for (long t = 0; t < 120_000 && fx.adapterResetRequests() == 0; t += TICK_STEP_MS) {
            fx.advance(TICK_STEP_MS);
            fx.port().hasNative = true; // stays discoverable across the rejected attempts
            fx.tick();
        }
        assertEquals(1, fx.adapterResetRequests(),
                "a persistent rejection streak is the wedged-controller case: exactly one budgeted reset");
    }

    @Test
    void staleBondIsClearedOnlyOnEvidenceNeverOnANormalConnect() {
        FakeDevicePort port = connectReady();
        port.prePaired = true;

        // Normal successful connect: stored keys must be reused, never cleared.
        assertTrue(runUntil(() -> port.connectNativeCalls >= 1, 10_000) >= 0);
        port.settleNativeConnected();
        fx.fireConnectedEvent();
        fx.tick();
        assertTrue(runUntil(() -> port.gattResolved, 30_000) >= 0);
        assertEquals(0, port.clearStalePairingCalls, "a healthy encrypted reconnect must reuse the bond");

        // Dead-bond evidence: a pre-paired connect that never establishes and times out.
        port.silentDrop();
        assertTrue(runUntil(() -> !port.flagConnected, 30_000) >= 0);
        fx.port().hasNative = true;
        int attemptsBefore = port.connectNativeCalls;
        assertTrue(runUntil(() -> port.connectNativeCalls > attemptsBefore, 30_000) >= 0);
        assertTrue(runUntil(() -> port.clearStalePairingCalls >= 1, 60_000) >= 0,
                "a pre-paired connect that never establishes is the dead-bond case: clear and re-pair fresh");
    }

    // --- adapter coordination --------------------------------------------------------------------------

    @Test
    void unhealthyAdapterFreezesTheDeviceInsteadOfChurningIt() {
        FakeDevicePort port = connectReady();
        fx.setAdapterHealthy(false);
        runFor(30_000);
        assertEquals(0, port.connectNativeCalls, "no radio work against an unhealthy adapter");
        assertEquals(0, fx.adapterResetRequests(), "a paused device must not age toward escalation");

        fx.setAdapterHealthy(true);
        assertTrue(runUntil(() -> port.connectNativeCalls >= 1, 10_000) >= 0,
                "recovery resumes where it left off once the adapter is healthy");
    }

    // --- production incident replays ---------------------------------------------------------------------

    @Test
    void scenario_asymmetricRfFade_recoversWhenTheFadePasses() {
        // 2026-07-16 14:07 (btmon-proven): five establishment failures ~300ms each (the node never heard the
        // CONNECT_INDs), then the fade passes and the link must come up and resolve without manual help.
        FakeDevicePort port = connectReady();
        int failures = 0;
        long start = fx.nowMillis();
        while (failures < 5) {
            final int target = port.connectNativeCalls + 1;
            assertTrue(runUntil(() -> port.connectNativeCalls >= target, 60_000) >= 0,
                    "retries must continue through the fade");
            fx.advance(300);
            fx.fireDisconnectedEvent();
            fx.tick();
            fx.fireHandleFoundEvent();
            fx.tick();
            failures++;
        }
        final int target = port.connectNativeCalls + 1;
        assertTrue(runUntil(() -> port.connectNativeCalls >= target, 60_000) >= 0);
        port.settleNativeConnected();
        fx.fireConnectedEvent();
        fx.tick();
        assertTrue(runUntil(() -> port.gattResolved, 30_000) >= 0, "fade over: full recovery to resolved GATT");
        assertTrue(fx.nowMillis() - start < 300_000, "the whole episode must complete in minutes, not hours");
    }

    @Test
    void scenario_permanentlyInvisibleDevice_doesNotEscalateForever() {
        // 2026-07-16 zombie/dead-battery lesson: a device that can never be discovered again must keep
        // wanting discovery without wedging or spamming adapter resets.
        FakeDevicePort port = fx.port();
        port.wanted = true;
        port.hasNative = false;
        runFor(300_000);
        assertTrue(fx.wantsDiscovery(), "still hunting");
        assertEquals(0, port.connectNativeCalls, "no handle, no connect attempts");
        assertTrue(fx.adapterResetRequests() <= 1, "an absent peer is not an adapter fault");
    }
}
