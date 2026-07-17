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
import java.util.function.BooleanSupplier;

import org.direct_bt.HCIStatusCode;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.device.DeviceActor;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.device.DeviceActorDiagnostics;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.device.DeviceActorRuntime;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.device.DeviceActorState;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.device.DeviceBackoffPolicy;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.device.DeviceWaitingOn;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.effect.SettleTimerEffectExecutor;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.event.DeviceEffect;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.event.DeviceEvent;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.port.DevicePort;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.procedure.ConnectProcedure;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.procedure.DeviceProcedure;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.procedure.DeviceProcedureName;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.procedure.OnlineMonitorProcedure;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.procedure.ResolveGattProcedure;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.procedure.SettleLinkProcedure;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.procedure.SubscribeNotificationsProcedure;
import org.slf4j.Logger;

/**
 * Reconciles one device's connection lifecycle (design table rows 4-7), owned 1:1 by a Direct-BT device.
 * <p>
 * Desired = CONNECTED iff the device is enabled and the core currently wants a connection. Observed = polled
 * native truth ({@link DevicePort#isNativeConnected()} etc.), never the remembered flag or a command return-code.
 * Each tick does, in order:
 * <ol>
 * <li><b>state-flag sync</b> — drive our openHAB flag to match native truth (this is what makes the core's
 * reconnect loop fire on a silent drop, since the core reconciles against our flag);</li>
 * <li><b>connection</b> — if wanted, not connected, not already connecting, and the adapter scan is observed
 * OFF (the controller rejects create-connection while scanning), issue {@link DevicePort#connectNative()};</li>
 * <li><b>pending-stuck</b> — if CONNECTING longer than the connect deadline with no native connection, clear the
 * stuck create-connection via {@link DevicePort#disconnectNative()}, and past a harder deadline request an
 * adapter reset (shared budget);</li>
 * <li><b>gatt</b> — if connected but GATT not resolved, drive the actor-owned settle/resolve/subscribe
 * pipeline (see {@code driveProductionGattPipeline}).</li>
 * </ol>
 * The state-flag sync sub-step runs even while the adapter reconciler is unhealthy (it is pure observe->cleanup
 * with no radio command, and a just-reset adapter means every device must be marked disconnected); the connect /
 * gatt sub-steps only run when the device reconciler itself is un-paused (i.e. adapter healthy).
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
public class DeviceReconciler extends Reconciler<Boolean, DeviceReconciler.Observed> {

    /** Observed native device truth. */
    public static final class Observed {
        public final boolean hasNative;
        public final boolean nativeConnected;
        public final boolean gattResolved;
        public final boolean flagConnected;
        public final boolean flagConnecting;
        public final boolean pairing;

        Observed(boolean hasNative, boolean nativeConnected, boolean gattResolved, boolean flagConnected,
                boolean flagConnecting, boolean pairing) {
            this.hasNative = hasNative;
            this.nativeConnected = nativeConnected;
            this.gattResolved = gattResolved;
            this.flagConnected = flagConnected;
            this.flagConnecting = flagConnecting;
            this.pairing = pairing;
        }
    }

    // How long a CONNECTING may sit with no native connection before we clear the (likely stuck) create-connection.
    private static final long CONNECT_DEADLINE_MS = 8000;
    // How long pending-stuck may persist before we escalate to an adapter reset request.
    private static final long PENDING_RESET_AFTER_MS = 16000;
    // Minimum spacing between connectNative() attempts.
    private static final long CONNECT_RETRY_MS = 2000;
    // Deadlines for the post-connect pipeline procedures (settle -> resolve -> subscribe). Settle/subscribe
    // are fallbacks well above their expected sub-second phases; the resolve deadline equals the legacy
    // in-flight cap.
    private static final long SETTLE_DEADLINE_MS = 5000;
    private static final long SUBSCRIBE_DEADLINE_MS = 5000;

    private final DevicePort port;
    private final ResetBudget resetBudget;
    private final Runnable requestAdapterReset;
    private final DeviceActorRuntime productionRuntime;

    // Epoch millis at which the current attempt's native connect was issued (0 = none / lease not yet
    // granted); drives the pending-stuck pairing freeze and the wedge escalation. Retry pacing lives in the
    // actor runtime (lastConnectStartedAt).
    private long connectingSince;
    // Consecutive failed GATT resolves on a link the flags claim is connected (stale-flag detector).
    private static final int RESOLVE_FAIL_STREAK_LIMIT = 3;

    // Longest an in-flight GATT discovery is trusted as "progress". A legitimate discovery is bounded by its
    // native per-op ~12 s timeouts (worst observed on prod: ~9 s; several timing-out ops still finish well
    // under a minute), so an in-flight state older than this means the discovery thread hung and recovery
    // must proceed without it.
    private static final long RESOLVE_IN_FLIGHT_MAX_MS = 120_000;

    // How long after observing a fresh connection an INSTANT empty GATT resolve is treated as "native GATT
    // not servable yet" (warm-up) instead of a silent-drop symptom. Genuinely dead links fail via ATT
    // timeouts (10+ s per attempt), never instantly.
    private static final long GATT_WARMUP_GRACE_MS = 5000;

    // Minimum age of a freshly observed connection before the first GATT discovery is attempted. The controller can
    // report the ACL as connected before the ATT/L2CAP path is usable; probing native GATT during that gap returns an
    // instant empty model and was observed to coincide with fast 0x3e disconnects on the HP link.
    private static final long GATT_FIRST_RESOLVE_DELAY_MS = 500;

    // Consecutive COMMAND_DISALLOWED connect rejections before the adapter reset is requested. One or two are
    // the benign connect/scan race; a persistent streak is the wedged-controller case.
    private static final int COMMAND_DISALLOWED_RESET_STREAK = 3;
    private int commandDisallowedStreak;
    private int resolveFailStreak;
    // Epoch millis of the tick at which we first observed the current in-flight GATT discovery (0 = none).
    private long resolveInFlightSince;
    // Epoch millis of the tick at which we last transitioned flag to CONNECTED (0 = never observed).
    private long connectedObservedAt;
    // Epoch millis of the tick at which we first observed pairing in progress during the current CONNECTING window,
    // or 0 if not currently pairing. Used to freeze the connect deadline across an SMP negotiation (see act()).
    private long pairingSince;
    // Last pairing state mirrored into the production actor (rising/falling edges become Pairing* events, whose
    // transitions reset the actor's state clock — the actor-side equivalent of the connect-deadline freeze).
    private boolean pairingMirroredToActor;
    // Last connection intent mirrored into the production actor (see syncProductionIntent). Boxed: null means
    // "never mirrored", so the first observation always fires its edge.
    private @Nullable Boolean wantedMirroredToActor;
    // Gate timing diagnostics. These do not drive behavior; they explain where connection setup time is spent.
    private long waitingNativeHandleSince;
    private long waitingScanOffSince;
    private long waitingRetrySince;

    /**
     * @param scanIsOff returns the adapter's polled scan state == OFF (the actor's connect-lease executor
     *            grants the lease when this turns true; the controller rejects create-connection while scanning).
     * @param requestAdapterReset asks the adapter reconciler to reset (used when a create-connection is wedged).
     */
    public DeviceReconciler(Logger logger, DevicePort port, BooleanSupplier scanIsOff, ResetBudget resetBudget,
            Runnable requestAdapterReset) {
        this(logger, port, scanIsOff, resetBudget, requestAdapterReset, Clock.systemUTC());
    }

    public DeviceReconciler(Logger logger, DevicePort port, BooleanSupplier scanIsOff, ResetBudget resetBudget,
            Runnable requestAdapterReset, Clock clock) {
        super("dev:" + port.id(), logger, Boolean.FALSE, clock);
        this.port = port;
        this.resetBudget = resetBudget;
        this.requestAdapterReset = requestAdapterReset;
        this.productionRuntime = new DeviceActorRuntime(new DeviceActor(port.id(), logger, clock),
                DeviceReconciler::createProductionProcedure, scanIsOff, port, this::observeProductionRuntimeEvent,
                new SettleTimerEffectExecutor(clock::millis, GATT_FIRST_RESOLVE_DELAY_MS),
                new DeviceBackoffPolicy(port), this::onProductionBackoff);
    }

    /**
     * @return true iff this device needs the scan ON to make progress, i.e. it is wanted and we do not yet hold a
     *         usable native handle for it (cold start: never discovered; or the handle was cleared on a drop). Once
     *         we hold a handle the device wants the scan OFF instead (connectLE is rejected while scanning), so it
     *         stops wanting discovery and the connect step takes over. This split is what breaks the cold-start
     *         deadlock: the older single "wantsConnectWindow" predicate required a native handle to want the scan,
     *         so a never-seen device could never trigger the scan that would have discovered it (no handle -> no
     *         scan -> never discovered -> never a handle).
     */
    public boolean wantsDiscovery() {
        return port.isWanted() && !port.hasNativeDevice();
    }

    /**
     * @return true iff this device is wanted but has a native handle and is not yet natively connected — i.e. it is
     *         trying to <em>establish</em> its link (connecting now, or between connect attempts in the retry/backoff
     *         gap). Used by the bridge to make background/inbox discovery yield to a device that still needs to
     *         connect (a scan restarting between attempts starves the create-connection). This is deliberately NOT
     *         gated on {@code flagConnecting} alone, which is only true for the brief in-flight instant.
     */
    public boolean needsConnection() {
        return port.isWanted() && port.hasNativeDevice() && !port.isNativeConnected();
    }

    /**
     * Whether this device is natively connected but still resolving its GATT model. The link exists but the
     * service walk (many sequential ATT round-trips) is in flight — on a weak-RSSI link a concurrent LE scan
     * steals enough radio slots that the walk times out and restarts forever, so inbox/background scanning
     * must yield to this state exactly like it yields to {@link #needsConnection() establishing}.
     */
    public boolean isResolvingGatt() {
        return port.isWanted() && port.isNativeConnected() && !port.isGattResolved();
    }

    @Override
    protected Observed observe() {
        return new Observed(port.hasNativeDevice(), port.isNativeConnected(), port.isGattResolved(),
                port.isFlagConnected(), port.isFlagConnecting(), port.isPairing());
    }

    @Override
    protected boolean inSync(Boolean unusedDesired, Observed o) {
        boolean wanted = port.isWanted();
        if (!wanted) {
            // Not wanted: in sync once both the native ACL and our openHAB flag are down. A native handle may
            // remain cached for later reconnect/discovery; the live connection must not.
            return !o.nativeConnected && !o.flagConnected && !o.flagConnecting;
        }
        // Wanted: in sync iff natively connected, flag agrees, and GATT resolved.
        return o.hasNative && o.nativeConnected && o.flagConnected && o.gattResolved;
    }

    @Override
    protected void act(Boolean unusedDesired, Observed o) {
        long now = clock.millis();
        syncProductionIntent();
        syncProductionRuntime(o);

        // (5) STATE-FLAG SYNC — always reconcile our flag to native truth first.
        if (o.hasNative && o.nativeConnected && !o.flagConnected) {
            logger.debug("[reconcile:{}] native connected but flag not; marking connected", name);
            connectedObservedAt = now;
            clearConnectWindow();
            port.markConnected();
        } else if ((!o.hasNative || !o.nativeConnected) && o.flagConnected) {
            // Native says not-connected while we think CONNECTED -> the silent-drop case. Drive the disconnect
            // transition so the core's reconnect loop resumes. markDisconnected() also clears the stale native
            // handle, so this tick's observed snapshot is now out of date: return and let the next tick re-observe
            // (it will see hasNative==false and route through discovery before any reconnect).
            logger.debug("[reconcile:{}] native not connected but flag connected; marking disconnected", name);
            clearConnectWindow();
            port.markDisconnected();
            return;
        }

        if (!port.isWanted()) {
            if (o.hasNative && o.nativeConnected) {
                logger.debug("[reconcile:{}] no longer wanted but still connected; disconnecting", name);
                port.disconnectNative();
                port.markDisconnected();
            } else if (o.flagConnected || o.flagConnecting) {
                port.markDisconnected();
            }
            return; // nothing more to do for an unwanted device
        }

        // (7) GATT — connected but not resolved.
        if (o.hasNative && o.nativeConnected) {
            // SECURITY ENFORCEMENT (must run BEFORE GATT is exposed): if the device's configured security
            // requirement is not actually met on this link (e.g. "pin"/authenticated was requested but SMP
            // negotiated down to Just-Works or unencrypted because the peer can't do MITM), REFUSE the connection
            // rather than expose GATT over a weaker-than-demanded link. Selecting an authenticated mode must fail
            // closed, never silently downgrade — otherwise the mode is misleading and defeats its own purpose.
            if (port.securityRequirementUnmet()) {
                logger.warn("[reconcile:{}] required connection security not met on the link; refusing (no downgrade)",
                        name);
                port.disconnectNative();
                clearConnectWindow();
                port.markDisconnected();
                return;
            }
            if (!o.gattResolved) {
                driveProductionGattPipeline(now);
            } else {
                resolveFailStreak = 0;
                resolveInFlightSince = 0;
            }
            return; // connected: no connect work
        }

        // From here: wanted AND not natively connected.
        resolveInFlightSince = 0; // any in-flight-discovery age belongs to the connection that just ended

        // (6) ATTEMPT IN FLIGHT — the actor's CONNECT procedure covers BOTH phases of an attempt: the
        // CONNECT_LEASE wait (scan has not yielded yet; no native command issued) and the native-connect
        // window (connectLE issued, waiting for establishment). Each phase carries its own actor deadline.
        DeviceActorDiagnostics connectDiagnostics = productionRuntime.diagnostics();
        boolean attemptInFlight = connectDiagnostics.activeProcedureName() == DeviceProcedureName.CONNECT
                && connectDiagnostics.state() == DeviceActorState.CONNECTING;
        if (attemptInFlight && !o.flagConnecting) {
            // Lease wait: the adapter coordinator's discovery slice has the radio; the lease effect executor
            // grants inside the runtime tick (syncProductionRuntime) the moment the scan is observed OFF, and
            // the CONNECT_LEASE deadline bounds a scan that never stops. Nothing to do here but note it.
            if (waitingScanOffSince == 0) {
                waitingScanOffSince = now;
                logger.debug("[reconcile:{}] connect attempt waiting for the connect lease (scan busy)", name);
            }
            return;
        }
        if (attemptInFlight && o.flagConnecting) {
            if (waitingScanOffSince != 0) {
                logger.debug("[reconcile:{}] connect lease granted after {}ms", name, now - waitingScanOffSince);
                waitingScanOffSince = 0;
            }
            if (connectingSince == 0) {
                // The native connect was actually issued (lease granted, connectLE sent): the pending-stuck /
                // wedge window starts HERE, not at procedure start — a long-but-legitimate lease wait must
                // never count toward the "create-connection wedged" adapter-reset escalation.
                connectingSince = now;
            }
            // FREEZE THE DEADLINE DURING SMP NEGOTIATION. With setConnSecurityAuto the transport iterates the
            // security ladder, doing several connect/disconnect cycles to negotiate keys; that churn is correct
            // pairing behaviour but leaves no stable native link for seconds. Without this freeze the pending-stuck
            // deadline below would fire mid-negotiation and disconnectNative() tears it down (the endless
            // connect/clear-pending flap we saw live when setConnSecurityAuto was first tried). So while the device
            // reports pairing, hold connectingSince at "now minus whatever it was before pairing began", i.e. shift
            // it forward by each paused interval, exactly the freeze discipline the base Reconciler uses for paused
            // timers. When pairing ends (COMPLETED/FAILED/NONE) the deadline resumes from where it froze.
            if (o.pairing) {
                if (pairingSince == 0) {
                    pairingSince = now;
                    logger.debug("[reconcile:{}] pairing in progress; freezing connect deadline", name);
                }
                // The SMP ladder's own connect/disconnect churn fires deviceDisconnected events; discard them so
                // they cannot leak past the freeze and fast-fail the attempt once pairing ends.
                port.consumeConnectAttemptFailedEvent();
                return; // negotiating: make no progress judgement, let SMP run
            } else if (pairingSince != 0) {
                connectingSince += now - pairingSince; // shift deadline forward by the paused (pairing) duration
                pairingSince = 0;
                logger.debug("[reconcile:{}] pairing ended; resuming connect deadline", name);
            }
            // EVENT-DRIVEN FAST RETRY: native already told us this attempt is over (deviceDisconnected while
            // CONNECTING, e.g. a 0x3e establishment failure known within ~2 s). Waiting out the deadline would
            // add ~10 s per retry for nothing — clear pending now; normal retry pacing reconnects. The deadline
            // below remains the fallback for the silent case where no event is delivered.
            if (port.consumeConnectAttemptFailedEvent()) {
                logger.debug(
                        "[reconcile:{}] connect attempt reported failed by native event after {}ms; clearing pending",
                        name, now - connectingSince);
                // The actor owns the failure handling: ConnectProcedure emits the best-effort native disconnect,
                // enters BACKING_OFF, the backoff policy marks disconnected (+ stale-bond self-heal when the
                // device is pre-paired), and onProductionBackoff() closes the connect window.
                productionRuntime.submit(new DeviceEvent.ConnectFailed(productionRuntime.generation(),
                        "NATIVE_DISCONNECT_EVENT", port.hasStalePairing()));
                return;
            }
            // The 8 s "CONNECTING with no native link" deadline is actor-owned now: syncProductionRuntime()
            // ticks the CONNECT procedure, whose max-residency expiry produces the same disconnect/backoff/
            // clear sequence as the evented failure above (frozen-design constraint 5). The 16 s wedge
            // escalation lives in onProductionBackoff(), which sees the pending window's age before it closes.
            return; // give the current attempt its deadline before issuing another connect
        }

        // (4) CONNECTION — the actor owns scan gating (connect lease) and the attempt window; the reconciler
        // decides only WHEN a fresh attempt may start: a native handle must exist and retries are spaced.
        if (!port.hasNativeDevice()) {
            // No native handle yet (cold start, or just cleared on a drop). Discovery must surface it first; the
            // discovery reconciler keeps the scan ON for us because wantsDiscovery() is true.
            if (waitingNativeHandleSince == 0) {
                waitingNativeHandleSince = now;
                logger.debug("[reconcile:{}] waiting for discovery/native handle before connect", name);
            }
            return;
        } else if (waitingNativeHandleSince != 0) {
            logger.debug("[reconcile:{}] native handle available after {}ms", name, now - waitingNativeHandleSince);
            waitingNativeHandleSince = 0;
        }
        long lastConnectStartedAt = productionRuntime.lastConnectStartedAt();
        if (lastConnectStartedAt != 0 && now - lastConnectStartedAt < CONNECT_RETRY_MS) {
            if (waitingRetrySince == 0) {
                waitingRetrySince = now;
            }
            return; // space out attempts
        } else if (waitingRetrySince != 0) {
            logger.debug("[reconcile:{}] connect retry spacing waited {}ms", name, now - waitingRetrySince);
            waitingRetrySince = 0;
        }
        startProductionConnect(now);
    }

    /**
     * Drive the actor-owned settle -> resolve -> subscribe pipeline for a connected link whose GATT is not yet
     * resolved. The actor owns the states, deadlines and teardown effects (frozen-design constraints 3+5); the
     * reconciler contributes what only observation can: retry pacing (one resolve request per reconcile tick,
     * the legacy cadence) and the TRUST-BUT-VERIFY judgment over attempt outcomes, fed to the actor as events
     * instead of acted on the port directly. The judgment is unchanged from the old inline branch: Direct-BT's
     * Java-side getConnected()/getConnectionHandle() can both go stale after a silent link drop, in which state
     * every resolve returns empty (GATTHandler nullptr) — a short streak of failed resolves IS the disconnect
     * signal, with two exemptions: an in-flight discovery is progress (bounded by the in-flight cap), and an
     * INSTANT empty resolve on a young link is ATT warm-up, not evidence (a genuinely dead link fails SLOWLY).
     */
    private void driveProductionGattPipeline(long now) {
        DeviceProcedureName active = productionRuntime.diagnostics().activeProcedureName();
        boolean inPipeline = active == DeviceProcedureName.SETTLE_LINK || active == DeviceProcedureName.RESOLVE_GATT
                || active == DeviceProcedureName.SUBSCRIBE_NOTIFICATIONS
                || active == DeviceProcedureName.ONLINE_MONITOR;
        if (!inPipeline && connectedObservedAt != 0 && now - connectedObservedAt < GATT_FIRST_RESOLVE_DELAY_MS) {
            // Fresh connection outside the actor's own connect handoff: enter via SETTLE_LINK so the first
            // resolve still honours the fresh-link settle delay. (An ADOPTED live link — flag already
            // connected before this reconciler ever saw the transition — resolves immediately below, exactly
            // like the legacy inline branch.)
            logger.debug("[reconcile:{}] connected but GATT unresolved; entering actor settle/resolve pipeline", name);
            productionRuntime.start(new SettleLinkProcedure(SETTLE_DEADLINE_MS), "wanted:gattUnresolved");
            drainUnhandledProductionEffects();
            return;
        }
        if (active == DeviceProcedureName.RESOLVE_GATT && port.isGattResolving()) {
            // In-flight discovery is progress, not failure — but not forever: a discovery whose thread hung
            // in native code would otherwise suppress recovery indefinitely.
            resolveFailStreak = 0;
            if (resolveInFlightSince == 0) {
                resolveInFlightSince = now;
            }
            if (now - resolveInFlightSince > RESOLVE_IN_FLIGHT_MAX_MS) {
                logger.warn(
                        "[reconcile:{}] GATT resolve in flight for {}ms (cap {}ms); treating the discovery as hung and tearing down",
                        name, now - resolveInFlightSince, RESOLVE_IN_FLIGHT_MAX_MS);
                resolveInFlightSince = 0;
                productionRuntime
                        .submit(new DeviceEvent.GattResolveFailed(productionRuntime.generation(), "RESOLVE_HUNG"));
            } else {
                logger.debug("[reconcile:{}] GATT resolve already in flight; waiting", name);
                productionRuntime.tick(false);
            }
            drainUnhandledProductionEffects();
            return;
        }
        resolveInFlightSince = 0;
        long resolveStarted = clock.millis();
        boolean attempted;
        if (!inPipeline) {
            // Adopted/settled link with no pipeline running: enter RESOLVE_GATT directly; its start performs
            // the first attempt inside this call (skipped by the executor if a discovery is already in flight).
            logger.debug("[reconcile:{}] connected but GATT unresolved; entering actor resolve pipeline", name);
            productionRuntime.start(new ResolveGattProcedure(RESOLVE_IN_FLIGHT_MAX_MS), "wanted:gattUnresolved");
            attempted = true;
        } else if (active == DeviceProcedureName.RESOLVE_GATT) {
            logger.debug("[reconcile:{}] connected but GATT unresolved; resolving", name);
            productionRuntime.submit(new DeviceEvent.GattResolveRequested(productionRuntime.generation()));
            attempted = true;
        } else {
            // LINK_SETTLING (or a stale later phase): the tick advances the settle timer; its expiry hands
            // off to RESOLVE_GATT, whose start performs the first attempt inside this same call.
            productionRuntime.tick(false);
            attempted = productionRuntime.diagnostics().activeProcedureName() == DeviceProcedureName.RESOLVE_GATT
                    || port.isGattResolved();
            if (!attempted) {
                logger.debug("[reconcile:{}] connected but GATT unresolved; waiting out the settle window", name);
            }
        }
        drainUnhandledProductionEffects();
        if (!attempted) {
            return;
        }
        long resolveElapsed = clock.millis() - resolveStarted;
        if (!port.isGattResolved()) {
            if (port.isGattResolving()) {
                resolveFailStreak = 0;
                if (resolveInFlightSince == 0) {
                    resolveInFlightSince = now;
                }
                logger.debug("[reconcile:{}] GATT resolve still in flight after {}ms; waiting", name, resolveElapsed);
            } else if (connectedObservedAt != 0 && now - connectedObservedAt < GATT_WARMUP_GRACE_MS
                    && resolveElapsed < 500) {
                logger.debug("[reconcile:{}] GATT not servable yet {}ms after connect; retrying", name,
                        now - connectedObservedAt);
            } else if (++resolveFailStreak >= RESOLVE_FAIL_STREAK_LIMIT) {
                logger.warn(
                        "[reconcile:{}] GATT resolve failed {} times on a supposedly-connected link; treating as silently dropped",
                        name, resolveFailStreak);
                resolveFailStreak = 0;
                productionRuntime
                        .submit(new DeviceEvent.GattResolveFailed(productionRuntime.generation(), "SILENT_DROP"));
                drainUnhandledProductionEffects();
            } else {
                logger.debug("[reconcile:{}] GATT resolve attempt failed after {}ms (streak {}/{})", name,
                        resolveElapsed, resolveFailStreak, RESOLVE_FAIL_STREAK_LIMIT);
            }
        } else {
            resolveFailStreak = 0;
            resolveInFlightSince = 0;
            logger.debug("[reconcile:{}] GATT resolve completed in {}ms", name, resolveElapsed);
        }
    }

    /** Close the current CONNECTING window: clears both the connect deadline and any in-flight pairing freeze. */
    private void clearConnectWindow() {
        connectingSince = 0;
        pairingSince = 0;
    }

    private void startProductionConnect(long now) {
        connectingSince = 0; // stamped when the native connect is actually issued (lease granted)
        pairingMirroredToActor = false;
        logger.debug("[reconcile:{}] starting actor-owned connect attempt", name);
        productionRuntime.start(new ConnectProcedure(CONNECT_DEADLINE_MS), "wanted:connectReady");
        drainUnhandledProductionEffects();
        if (port.isFlagConnecting()) {
            connectingSince = now; // synchronous lease grant: the scan was already off and connectLE went out
        }

        DeviceActorDiagnostics diagnostics = productionRuntime.diagnostics();
        if (diagnostics.state() == DeviceActorState.CONNECTING
                && diagnostics.waitingOn() == DeviceWaitingOn.NATIVE_CONNECT && port.isFlagConnecting()) {
            commandDisallowedStreak = 0;
        }
        // A synchronous rejection already went through onProductionBackoff() (the runtime applies the backoff
        // policy inside start()), which closed the connect window — no diagnostics sniffing needed here.
    }

    /**
     * Mirror the connection INTENT into the production actor on every edge. Intent is the one input the actor
     * cannot observe for itself, and it outlives any single attempt: {@code AdapterResetCompleted} re-parks the
     * actor by intent (DISCOVERING vs IDLE_DISABLED), so a stale intent would strand a wanted device after a
     * reset. Wanted-offline also cancels whatever is in flight — critically a CONNECT_LEASE wait, where a later
     * lease grant would otherwise issue connectLE for a device nobody wants anymore — and settles a parked
     * pipeline procedure, so diagnostics read IDLE_DISABLED while unwanted.
     */
    private void syncProductionIntent() {
        boolean wanted = port.isWanted();
        if (wantedMirroredToActor != null && wantedMirroredToActor == wanted) {
            return;
        }
        wantedMirroredToActor = wanted;
        DeviceActorState before = productionRuntime.diagnostics().state();
        productionRuntime.submit(wanted ? new DeviceEvent.WantedOnline() : new DeviceEvent.WantedOffline());
        drainUnhandledProductionEffects();
        if (!wanted) {
            clearConnectWindow();
        }
        logger.debug("[reconcile:{}] connection intent -> {} (actor {} -> {})", name, wanted ? "online" : "offline",
                before, productionRuntime.diagnostics().stateName());
    }

    /**
     * Mirror the async connect-attempt outcomes into the production actor and drive its deadline. Runs at the
     * top of every act(). On NativeConnected the CONNECT procedure hands off to the actor-owned post-connect
     * pipeline (settle -> resolve -> subscribe -> online), which {@link #driveProductionGattPipeline} advances
     * from the connected branch of act().
     */
    private void syncProductionRuntime(Observed o) {
        DeviceActorDiagnostics diagnostics = productionRuntime.diagnostics();
        boolean connectInFlight = diagnostics.activeProcedureName() == DeviceProcedureName.CONNECT
                && diagnostics.state() == DeviceActorState.CONNECTING;
        if (!connectInFlight) {
            drainUnhandledProductionEffects();
            return;
        }
        if (o.hasNative && o.nativeConnected) {
            // The attempt succeeded: the procedure records it and hands off (the settle handoff effect is
            // outside the production slice and gets drained below); the rejection streak is over.
            commandDisallowedStreak = 0;
            productionRuntime.submit(new DeviceEvent.NativeConnected(productionRuntime.generation()));
        } else {
            // Mirror pairing edges so the actor's state clock freezes/resets exactly like the reconciler's
            // connect-deadline freeze: PairingStarted/Ended transitions restart the residency window, and
            // ticks are paused while pairing so a long SMP ladder cannot expire the deadline mid-negotiation.
            if (o.pairing != pairingMirroredToActor) {
                pairingMirroredToActor = o.pairing;
                productionRuntime.submit(o.pairing ? new DeviceEvent.PairingStarted(productionRuntime.generation())
                        : new DeviceEvent.PairingEnded(productionRuntime.generation()));
            }
            productionRuntime.tick(o.pairing);
        }
        drainUnhandledProductionEffects();
    }

    /** Safety valve: any actor effect no executor claimed is drained and logged rather than silently dropped. */
    private void drainUnhandledProductionEffects() {
        for (DeviceEffect effect : productionRuntime.drainUnhandledEffects()) {
            logger.debug("[reconcile:{}] actor effect outside the production slice: {}", name, effect.operation());
        }
    }

    /**
     * The production CONNECT procedure entered BACKING_OFF (evented failure, sync rejection, or its deadline).
     * The backoff policy has already marked the port disconnected (+ stale-bond self-heal); here the reconciler
     * closes its connect window and applies the wedge escalation the old inline deadline path carried: a pending
     * window that survived past the hard deadline means the create-connection is wedged at the controller.
     */
    private void onProductionBackoff(DeviceActorDiagnostics diagnostics) {
        if (connectingSince != 0) {
            long connectingFor = clock.millis() - connectingSince;
            if (connectingFor > PENDING_RESET_AFTER_MS && resetBudget.tryReset(name)) {
                logger.warn("[reconcile:{}] create-connection wedged {}ms; requesting adapter reset", name,
                        connectingFor);
                requestAdapterReset.run();
            }
        }
        clearConnectWindow();
    }

    public @Nullable Observed lastObserved() {
        return observed;
    }

    /**
     * Adapter reset fanout (frozen constraint 12): an adapter reset is a generation boundary for every device
     * on that adapter. Started cancels the active procedure and bumps the device generation, so every event
     * and effect from the pre-reset world is fenced as stale; the backoff policy clears the port (handles
     * dropped, rediscovery from a fresh advert). Local judgment state falls with it. Must be called on the
     * reconcile tick thread — the actor runtime is caller-threaded.
     */
    public void onAdapterResetStarted(long adapterGeneration) {
        productionRuntime.submit(new DeviceEvent.AdapterResetStarted(adapterGeneration));
        drainUnhandledProductionEffects();
        clearConnectWindow();
        commandDisallowedStreak = 0;
        resolveFailStreak = 0;
        resolveInFlightSince = 0;
    }

    /**
     * Adapter reset finished (any result): the actor re-parks by intent — DISCOVERING when the device asked
     * to be online before, IDLE_DISABLED otherwise — and the next reconcile drives recovery as a cold start.
     */
    public void onAdapterResetCompleted(long adapterGeneration, @Nullable String result) {
        productionRuntime.submit(new DeviceEvent.AdapterResetCompleted(adapterGeneration, result));
        drainUnhandledProductionEffects();
    }

    public DeviceActorDiagnostics actorDiagnostics() {
        return productionRuntime.diagnostics();
    }

    DeviceActorRuntime productionRuntimeForTest() {
        return productionRuntime;
    }

    int commandDisallowedStreakForTest() {
        return commandDisallowedStreak;
    }

    private void observeProductionRuntimeEvent(DeviceEvent event) {
        if (event instanceof DeviceEvent.NativeConnected) {
            commandDisallowedStreak = 0;
            return;
        }
        if (event instanceof DeviceEvent.ConnectFailed) {
            DeviceEvent.ConnectFailed failed = (DeviceEvent.ConnectFailed) event;
            if (HCIStatusCode.COMMAND_DISALLOWED.name().equals(failed.reason())) {
                if (++commandDisallowedStreak >= COMMAND_DISALLOWED_RESET_STREAK && resetBudget.tryReset(name)) {
                    logger.warn("[reconcile:{}] connect COMMAND_DISALLOWED x{}; requesting adapter reset", name,
                            commandDisallowedStreak);
                    commandDisallowedStreak = 0;
                    requestAdapterReset.run();
                } else {
                    logger.debug("[reconcile:{}] connect COMMAND_DISALLOWED (streak {}/{}); retrying", name,
                            commandDisallowedStreak, COMMAND_DISALLOWED_RESET_STREAK);
                }
            }
        }
    }

    /**
     * The production actor runs the full lifecycle chain: CONNECT hands off to SETTLE_LINK on native
     * connection, whose timer expiry hands off to RESOLVE_GATT, then SUBSCRIBE_NOTIFICATIONS, then the
     * (deadline-free) ONLINE_MONITOR. The reconciler paces resolve retries and feeds outcome judgments
     * as events; see {@link #driveProductionGattPipeline}.
     */
    private static @Nullable DeviceProcedure createProductionProcedure(DeviceProcedureName procedureName) {
        if (procedureName == DeviceProcedureName.CONNECT) {
            return new ConnectProcedure(CONNECT_DEADLINE_MS);
        }
        if (procedureName == DeviceProcedureName.SETTLE_LINK) {
            return new SettleLinkProcedure(SETTLE_DEADLINE_MS);
        }
        if (procedureName == DeviceProcedureName.RESOLVE_GATT) {
            return new ResolveGattProcedure(RESOLVE_IN_FLIGHT_MAX_MS);
        }
        if (procedureName == DeviceProcedureName.SUBSCRIBE_NOTIFICATIONS) {
            return new SubscribeNotificationsProcedure(SUBSCRIBE_DEADLINE_MS);
        }
        if (procedureName == DeviceProcedureName.ONLINE_MONITOR) {
            return new OnlineMonitorProcedure();
        }
        return null;
    }
}
