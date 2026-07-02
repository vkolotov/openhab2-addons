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

    private final DevicePort port;
    private final BooleanSupplier scanIsOff;
    private final ResetBudget resetBudget;
    private final Runnable requestAdapterReset;

    // Timestamps (epoch millis). connectingSince drives the stuck deadline; lastConnectAttemptAt spaces retries.
    private long connectingSince;
    private long lastConnectAttemptAt;
    // Epoch millis of the tick at which we first observed pairing in progress during the current CONNECTING window,
    // or 0 if not currently pairing. Used to freeze the connect deadline across an SMP negotiation (see act()).
    private long pairingSince;

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

        // (5) STATE-FLAG SYNC — always reconcile our flag to native truth first.
        if (o.hasNative && o.nativeConnected && !o.flagConnected) {
            logger.debug("[reconcile:{}] native connected but flag not; marking connected", name);
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
                logger.debug("[reconcile:{}] connected but GATT unresolved; resolving", name);
                port.resolveGatt();
            }
            return; // connected: no connect work
        }

        // From here: wanted AND not natively connected.

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
                return; // negotiating: make no progress judgement, let SMP run
            } else if (pairingSince != 0) {
                connectingSince += now - pairingSince; // shift deadline forward by the paused (pairing) duration
                pairingSince = 0;
                logger.debug("[reconcile:{}] pairing ended; resuming connect deadline", name);
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
            return;
        }
        if (now - lastConnectAttemptAt < CONNECT_RETRY_MS) {
            return; // space out attempts
        }
        if (!scanIsOff.getAsBoolean()) {
            // We hold a native handle now, so the device no longer wants discovery; the discovery reconciler will
            // stop the scan (its desired flips to OFF), which then lets this connect fire on a later tick. Wait.
            logger.trace("[reconcile:{}] waiting for scan to stop before connect", name);
            return;
        }
        lastConnectAttemptAt = now;
        port.markConnecting();
        connectingSince = now;
        HCIStatusCode rc = port.connectNative();
        logger.debug("[reconcile:{}] connectNative -> {}", name, rc);
        if (rc != HCIStatusCode.SUCCESS) {
            // Command rejected outright (e.g. COMMAND_DISALLOWED): not connecting after all. Reset flag so the
            // next tick re-evaluates from DISCONNECTED rather than sitting in a phantom CONNECTING.
            clearConnectWindow();
            port.markDisconnected();
            if (rc == HCIStatusCode.COMMAND_DISALLOWED && resetBudget.tryReset(name)) {
                logger.warn("[reconcile:{}] connect COMMAND_DISALLOWED; requesting adapter reset", name);
                requestAdapterReset.run();
            }
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
}
