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
 * <li><b>gatt</b> — if connected but GATT not resolved, {@link DevicePort#resolveGatt()}.</li>
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
    private static final long SHADOW_SETTLE_DEADLINE_MS = 5000;
    private static final long SHADOW_SUBSCRIBE_DEADLINE_MS = 5000;

    private final DevicePort port;
    private final BooleanSupplier scanIsOff;
    private final ResetBudget resetBudget;
    private final Runnable requestAdapterReset;
    private final DeviceActorRuntime shadowRuntime;

    // Timestamps (epoch millis). connectingSince drives the stuck deadline; lastConnectAttemptAt spaces retries.
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
    private long lastConnectAttemptAt;
    // Epoch millis of the tick at which we first observed pairing in progress during the current CONNECTING window,
    // or 0 if not currently pairing. Used to freeze the connect deadline across an SMP negotiation (see act()).
    private long pairingSince;
    // Gate timing diagnostics. These do not drive behavior; they explain where connection setup time is spent.
    private long waitingNativeHandleSince;
    private long waitingScanOffSince;
    private long waitingRetrySince;

    /**
     * @param scanIsOff returns the adapter's polled scan state == OFF (connect step waits for this).
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
        this.scanIsOff = scanIsOff;
        this.resetBudget = resetBudget;
        this.requestAdapterReset = requestAdapterReset;
        this.shadowRuntime = new DeviceActorRuntime(new DeviceActor(port.id(), logger, clock),
                DeviceReconciler::createShadowProcedure, () -> false, new ShadowDevicePort(port.id()));
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
    protected void afterObserve(Boolean unusedDesired, Observed o) {
        boolean wanted = port.isWanted();
        if (!wanted) {
            if (o.nativeConnected || o.flagConnected || o.flagConnecting) {
                shadowRuntime.shadowObserve(DeviceActorState.DISCONNECTING, DeviceWaitingOn.DISCONNECT, "unwanted");
            } else {
                shadowRuntime.shadowObserve(DeviceActorState.IDLE_DISABLED, DeviceWaitingOn.NOTHING, "unwanted");
            }
            return;
        }
        if (!o.hasNative) {
            shadowRuntime.shadowObserve(DeviceActorState.DISCOVERING, DeviceWaitingOn.NATIVE_HANDLE,
                    "wanted:noHandle");
            return;
        }
        if (o.nativeConnected) {
            if (!o.gattResolved) {
                shadowRuntime.shadowObserve(DeviceActorState.RESOLVING_GATT, DeviceWaitingOn.GATT_RESOLVE,
                        "wanted:gattUnresolved");
            } else {
                shadowRuntime.shadowObserve(DeviceActorState.ONLINE, DeviceWaitingOn.NOTHING, "wanted:online");
            }
            return;
        }
        if (o.flagConnecting) {
            shadowRuntime.shadowObserve(DeviceActorState.CONNECTING,
                    o.pairing ? DeviceWaitingOn.PAIRING : DeviceWaitingOn.NATIVE_CONNECT,
                    o.pairing ? "wanted:pairing" : "wanted:connecting");
            return;
        }
        long now = clock.millis();
        if (lastConnectAttemptAt != 0 && now - lastConnectAttemptAt < CONNECT_RETRY_MS) {
            shadowRuntime.shadowObserve(DeviceActorState.BACKING_OFF, DeviceWaitingOn.BACKOFF_TIMER,
                    "wanted:connectRetry");
        } else {
            shadowRuntime.shadowObserve(DeviceActorState.CONNECTING, DeviceWaitingOn.CONNECT_LEASE,
                    scanIsOff.getAsBoolean() ? "wanted:connectReady" : "wanted:connectLease");
        }
    }

    @Override
    protected void act(Boolean unusedDesired, Observed o) {
        long now = clock.millis();

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
                if (connectedObservedAt != 0 && now - connectedObservedAt < GATT_FIRST_RESOLVE_DELAY_MS) {
                    logger.debug("[reconcile:{}] connected but GATT unresolved; waiting {}ms before first resolve",
                            name, GATT_FIRST_RESOLVE_DELAY_MS - (now - connectedObservedAt));
                    return;
                }
                logger.debug("[reconcile:{}] connected but GATT unresolved; resolving", name);
                if (port.isGattResolving()) {
                    resolveFailStreak = 0;
                    if (resolveInFlightSince == 0) {
                        resolveInFlightSince = now;
                    }
                    // In-flight discovery is progress, not failure — but not forever: a discovery whose
                    // thread hung in native code would otherwise suppress recovery indefinitely.
                    if (now - resolveInFlightSince > RESOLVE_IN_FLIGHT_MAX_MS) {
                        logger.warn(
                                "[reconcile:{}] GATT resolve in flight for {}ms (cap {}ms); treating the discovery as hung and tearing down",
                                name, now - resolveInFlightSince, RESOLVE_IN_FLIGHT_MAX_MS);
                        resolveInFlightSince = 0;
                        port.markDisconnected();
                    } else {
                        logger.debug("[reconcile:{}] GATT resolve already in flight; waiting", name);
                    }
                    return;
                }
                resolveInFlightSince = 0;
                long resolveStarted = now;
                port.resolveGatt();
                long resolveElapsed = clock.millis() - resolveStarted;
                // TRUST BUT VERIFY the "connected" verdict. Direct-BT's Java-side getConnected() and
                // getConnectionHandle() can both go stale after a silent link drop: they keep reporting a
                // connection while the native side has none and the device is out advertising. In that state
                // every resolve returns empty (GATTHandler nullptr) and this branch loops forever. A real,
                // healthy link resolves GATT in one or two attempts — so a short streak of failed resolves IS
                // the disconnect signal. Tear the flag down and let the normal rediscover->connect path
                // rebuild from a fresh advertisement.
                if (!port.isGattResolved()) {
                    if (port.isGattResolving()) {
                        resolveFailStreak = 0;
                        if (resolveInFlightSince == 0) {
                            resolveInFlightSince = now;
                        }
                        logger.debug("[reconcile:{}] GATT resolve still in flight after {}ms; waiting", name,
                                resolveElapsed);
                    } else if (connectedObservedAt != 0 && now - connectedObservedAt < GATT_WARMUP_GRACE_MS
                            && resolveElapsed < 500) {
                        // Not a failure verdict: an INSTANT empty resolve right after connect means native GATT
                        // is not servable yet (the event-expedited tick gets here ~300 ms after the connection
                        // event, before the ATT channel is up). A genuinely dead link fails SLOWLY (ATT
                        // timeouts, 10+ s), so "returned immediately, connection young" is warm-up, not
                        // evidence — retry next tick without burning the silent-drop streak.
                        logger.debug("[reconcile:{}] GATT not servable yet {}ms after connect; retrying", name,
                                now - connectedObservedAt);
                    } else if (++resolveFailStreak >= RESOLVE_FAIL_STREAK_LIMIT) {
                        logger.warn(
                                "[reconcile:{}] GATT resolve failed {} times on a supposedly-connected link; treating as silently dropped",
                                name, resolveFailStreak);
                        resolveFailStreak = 0;
                        port.markDisconnected();
                    } else {
                        logger.debug("[reconcile:{}] GATT resolve attempt failed after {}ms (streak {}/{})", name,
                                resolveElapsed, resolveFailStreak, RESOLVE_FAIL_STREAK_LIMIT);
                    }
                } else {
                    resolveFailStreak = 0;
                    resolveInFlightSince = 0;
                    logger.debug("[reconcile:{}] GATT resolve completed in {}ms", name, resolveElapsed);
                }
            } else {
                resolveFailStreak = 0;
                resolveInFlightSince = 0;
            }
            return; // connected: no connect work
        }

        // From here: wanted AND not natively connected.
        resolveInFlightSince = 0; // any in-flight-discovery age belongs to the connection that just ended

        // (6) PENDING-STUCK — a CONNECTING that never produced a native connection.
        if (o.flagConnecting && connectingSince != 0) {
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
                logger.debug("[reconcile:{}] connect attempt reported failed by native event after {}ms; clearing pending",
                        name, now - connectingSince);
                port.disconnectNative();
                if (port.hasStalePairing()) {
                    logger.debug("[reconcile:{}] failed while pre-paired; clearing stale bond to re-pair fresh", name);
                    port.clearStalePairing();
                }
                clearConnectWindow();
                port.markDisconnected();
                return;
            }
            long connectingFor = now - connectingSince;
            if (connectingFor > CONNECT_DEADLINE_MS) {
                logger.debug("[reconcile:{}] CONNECTING for {}ms with no native link; clearing pending", name,
                        connectingFor);
                port.disconnectNative();
                // STALE-BOND SELF-HEAL: a create-connection that never establishes while the device holds
                // pre-paired keys is the "dead bond" case — an encrypted reconnect that reuses a stored LTK the
                // peer no longer honours (e.g. a peripheral that forgot the bond / re-advertises fresh) silently
                // never completes. Clear the stale keys so the next attempt does a FRESH pairing instead of
                // reusing the dead LTK. Same "trust the fresh frame, not a cached object" discipline as clearing a
                // stale native handle on a silent drop. Cheap and safe for the non-pre-paired case (no-op there).
                if (port.hasStalePairing()) {
                    logger.debug("[reconcile:{}] stuck while pre-paired; clearing stale bond to re-pair fresh", name);
                    port.clearStalePairing();
                }
                if (connectingFor > PENDING_RESET_AFTER_MS && resetBudget.tryReset(name)) {
                    logger.warn("[reconcile:{}] create-connection wedged {}ms; requesting adapter reset", name,
                            connectingFor);
                    requestAdapterReset.run();
                }
                // Drop back to DISCONNECTED so the next tick can re-attempt a clean connect.
                clearConnectWindow();
                port.markDisconnected();
            }
            return; // give the current attempt its deadline before issuing another connect
        }

        // (4) CONNECTION — issue connectLE only when the scan is observed OFF (controller rejects it otherwise).
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
        if (now - lastConnectAttemptAt < CONNECT_RETRY_MS) {
            if (waitingRetrySince == 0) {
                waitingRetrySince = now;
            }
            return; // space out attempts
        } else if (waitingRetrySince != 0) {
            logger.debug("[reconcile:{}] connect retry spacing waited {}ms", name, now - waitingRetrySince);
            waitingRetrySince = 0;
        }
        if (!scanIsOff.getAsBoolean()) {
            // We hold a native handle now, so the device no longer wants discovery; the discovery reconciler will
            // stop the scan (its desired flips to OFF), which then lets this connect fire on a later tick. Wait.
            if (waitingScanOffSince == 0) {
                waitingScanOffSince = now;
                logger.debug("[reconcile:{}] waiting for scan to stop before connect", name);
            }
            return;
        } else if (waitingScanOffSince != 0) {
            logger.debug("[reconcile:{}] scan stopped after {}ms; connect gate open", name, now - waitingScanOffSince);
            waitingScanOffSince = 0;
        }
        lastConnectAttemptAt = now;
        port.markConnecting();
        connectingSince = now;
        long connectStarted = clock.millis();
        HCIStatusCode rc = port.connectNative();
        logger.debug("[reconcile:{}] connectNative -> {} in {}ms", name, rc, clock.millis() - connectStarted);
        if (rc != HCIStatusCode.SUCCESS) {
            // Command rejected outright (e.g. COMMAND_DISALLOWED): not connecting after all. Reset flag so the
            // next tick re-evaluates from DISCONNECTED rather than sitting in a phantom CONNECTING.
            clearConnectWindow();
            port.markDisconnected();
            if (rc == HCIStatusCode.COMMAND_DISALLOWED) {
                // A single COMMAND_DISALLOWED is usually the connect/scan race (the controller refuses
                // create-connection while a scan is starting/running) — retry via the normal path, which
                // re-gates on the scan being observed OFF. Only a PERSISTENT streak is the wedged-controller
                // (CSR quirk) case that justifies the adapter reset — a native call that has been observed to
                // hang, so it must be a last resort.
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
        } else {
            commandDisallowedStreak = 0;
        }
    }

    /** Close the current CONNECTING window: clears both the connect deadline and any in-flight pairing freeze. */
    private void clearConnectWindow() {
        connectingSince = 0;
        pairingSince = 0;
    }

    public @Nullable Observed lastObserved() {
        return observed;
    }

    public DeviceActorDiagnostics actorDiagnostics() {
        return shadowRuntime.diagnostics();
    }

    private static @Nullable DeviceProcedure createShadowProcedure(DeviceProcedureName procedureName) {
        if (procedureName == DeviceProcedureName.CONNECT) {
            return new ConnectProcedure(CONNECT_DEADLINE_MS);
        }
        if (procedureName == DeviceProcedureName.SETTLE_LINK) {
            return new SettleLinkProcedure(SHADOW_SETTLE_DEADLINE_MS);
        }
        if (procedureName == DeviceProcedureName.RESOLVE_GATT) {
            return new ResolveGattProcedure(RESOLVE_IN_FLIGHT_MAX_MS);
        }
        if (procedureName == DeviceProcedureName.SUBSCRIBE_NOTIFICATIONS) {
            return new SubscribeNotificationsProcedure(SHADOW_SUBSCRIBE_DEADLINE_MS);
        }
        if (procedureName == DeviceProcedureName.ONLINE_MONITOR) {
            return new OnlineMonitorProcedure();
        }
        return null;
    }

    private static final class ShadowDevicePort implements DevicePort {
        private final String id;

        ShadowDevicePort(String id) {
            this.id = id;
        }

        @Override
        public boolean isWanted() {
            return false;
        }

        @Override
        public boolean hasNativeDevice() {
            return false;
        }

        @Override
        public boolean isNativeConnected() {
            return false;
        }

        @Override
        public boolean isGattResolved() {
            return false;
        }

        @Override
        public boolean isGattResolving() {
            return false;
        }

        @Override
        public boolean isFlagConnected() {
            return false;
        }

        @Override
        public boolean isFlagConnecting() {
            return false;
        }

        @Override
        public boolean isPairing() {
            return false;
        }

        @Override
        public boolean consumeConnectAttemptFailedEvent() {
            return false;
        }

        @Override
        public void markConnected() {
        }

        @Override
        public void markDisconnected() {
        }

        @Override
        public void markConnecting() {
        }

        @Override
        public HCIStatusCode connectNative() {
            return HCIStatusCode.SUCCESS;
        }

        @Override
        public void disconnectNative() {
        }

        @Override
        public boolean hasStalePairing() {
            return false;
        }

        @Override
        public void clearStalePairing() {
        }

        @Override
        public boolean securityRequirementUnmet() {
            return false;
        }

        @Override
        public void resolveGatt() {
        }

        @Override
        public String id() {
            return id;
        }
    }
}
