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
package org.openhab.binding.bluetooth.directbt.internal;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.direct_bt.AdapterStatusListener;
import org.direct_bt.BTAdapter;
import org.direct_bt.BTDevice;
import org.direct_bt.BTManager;
import org.direct_bt.BTManager.ChangedAdapterSetListener;
import org.direct_bt.BTMode;
import org.direct_bt.DiscoveryPolicy;
import org.direct_bt.EIRDataTypeSet;
import org.direct_bt.HCIStatusCode;
import org.direct_bt.ScanType;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.bluetooth.AbstractBluetoothBridgeHandler;
import org.openhab.binding.bluetooth.BluetoothAddress;
import org.openhab.binding.bluetooth.BluetoothBindingConstants;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.AdapterReconciler;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.DeviceReconciler;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.ResetBudget;
import org.openhab.core.common.ThreadPoolManager;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bridge handler for a Direct-BT controlled Bluetooth adapter.
 * <p>
 * Owns one HCI adapter directly via the Direct-BT userspace stack (no bluetoothd). Loads the bundled
 * native libraries, resolves the configured adapter by MAC, drives discovery, and surfaces discovered
 * devices to the openHAB bluetooth core.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
public class DirectBTBridgeHandler extends AbstractBluetoothBridgeHandler<DirectBTBluetoothDevice> {

    private static final int POWER_ON_WAIT_TRIES = 20;
    private static final long POWER_ON_WAIT_MS = 100;

    private final Logger logger = LoggerFactory.getLogger(DirectBTBridgeHandler.class);

    // Dedicated pool for the blocking Direct-BT GATT read/write/notify-setup calls, so they never block the
    // scheduler or native callback threads.
    private final ExecutorService executor = ThreadPoolManager.getPool("bluetooth-directbt");

    // --- Level-triggered reconciler -----------------------------------------------------------------
    // Direct-BT is eventually-consistent: a command returning SUCCESS means only "accepted", not "done",
    // and its status events (deviceConnected/deviceDisconnected) are HINTS that may be dropped (the silent
    // controller-side ACL drop). So we do not sequence commands off events; instead one periodic reconciler
    // owns the radio: it polls the NATIVE truth (adapter.isDiscovering(), dev.getConnected()), compares to the
    // desired state, and issues the idempotent corrective command to close the gap. Events merely requeue an
    // immediate reconcile so we react fast; if an event is missed the next tick heals it anyway.
    private static final long RECONCILE_INTERVAL_MS = 2000;
    // Floor on event-driven native-observe frequency. An event may make a tick happen SOONER than the periodic
    // interval, but never MORE OFTEN than this: a reconcile tick polls native truth (adapter.isPowered/isValid,
    // per-device getConnected, isDiscovering), and those native reads are expensive and can themselves wedge a
    // marginal controller. Events burst precisely when the radio is unhealthy (a connect/disconnect storm, a
    // per-advert deviceFound flood), so capping observe frequency under load is a reliability requirement.
    private static final long MIN_OBSERVE_INTERVAL_MS = 300;
    // Hard cap on the best-effort native teardown in dispose() (device disconnect + adapter detach). The native
    // dev.disconnect() joins the L2CAP reader thread and can block forever on a wedged controller; capping it
    // keeps dispose() bounded so a JVM shutdown cannot hang (which previously got openHAB SIGKILLed by systemd).
    private static final long DISPOSE_NATIVE_TIMEOUT_MS = 3000;

    private @Nullable BluetoothAddress adapterAddress;
    private @Nullable BTManager manager;
    private @Nullable BTAdapter adapter;
    private @Nullable ScheduledFuture<?> initJob;
    // The level-triggered reconciler job (the only thing that drives discovery on/off and reconciles device state).
    private @Nullable ScheduledFuture<?> reconcileJob;
    // The single driver lock: the reconcile tick runs under it so reconcilers are effectively single-threaded
    // (at most one connectLE/startDiscovery in flight by construction). Event hints also requeue under it.
    private final Object reconcileLock = new Object();
    // Event-requeue coalescing: at most ONE event-driven tick may be queued/in-flight at a time. A burst of
    // events (OH connect/disconnect + native deviceFound/Connected/Disconnected/discoveringChanged) collapses
    // to a single tick that observes the latest native truth; the surplus requests do ZERO native reads.
    private final AtomicBoolean reconcilePending = new AtomicBoolean();
    // When the last reconcile tick (periodic or event-driven) last ran, for the MIN_OBSERVE_INTERVAL_MS floor.
    private volatile long lastTickAt;
    private final ResetBudget resetBudget = new ResetBudget(LoggerFactory.getLogger(ResetBudget.class));
    private @Nullable AdapterReconciler adapterReconciler;
    private boolean managerReady;
    private volatile boolean disposed;

    // NOTE: AdapterStatusListener's constructor only builds its native peer if BTFactory.isInitialized();
    // it must therefore be created AFTER getDirectBTManager(), not as a field initializer (otherwise its
    // native instance is null and addStatusListener() crashes with a null-reference). Created lazily.
    private @Nullable AdapterStatusListener statusListener;
    private final ChangedAdapterSetListener changedAdapterSetListener = new DirectBTChangedAdapterSetListener();

    /**
     * Direct-BT's {@code AdapterStatusListener} is an abstract class whose method parameters carry no
     * null annotations; opt this type out of {@code @NonNullByDefault} so the overrides match.
     */
    @NonNullByDefault({})
    private class DirectBTStatusListener extends AdapterStatusListener {
        @Override
        public boolean deviceFound(BTDevice device, long timestamp) {
            logger.debug("Direct-BT deviceFound: {}", device.getAddressAndType());
            DirectBTBluetoothDevice btDevice = onDeviceFound(device);
            requeueReconcile(); // hint: a wanted device may now be connectable
            // Direct-BT's return value is ownership, not discovery flow-control: false removes the native device
            // from the shared list and the BTDevice can no longer be used for a later connect. Keep only devices
            // with active connection intent; background advertisements can stay cheap/non-persistent.
            return btDevice != null && btDevice.isWanted();
        }

        @Override
        public void deviceUpdated(BTDevice device, EIRDataTypeSet updateMask, long timestamp) {
            logger.trace("Direct-BT deviceUpdated: {}", device.getAddressAndType());
            onDeviceFound(device);
        }

        @Override
        public void discoveringChanged(BTAdapter a, ScanType currentMeta, ScanType changedType, boolean changedEnabled,
                DiscoveryPolicy policy, long timestamp) {
            logger.debug("Direct-BT discoveringChanged: meta={} enabled={} policy={}", currentMeta, changedEnabled,
                    policy);
            requeueReconcile(); // hint: scan state changed; reconcile in case it diverged from desired
        }

        // The connection-state callbacks are HINTS ONLY. We do not drive openHAB state from them (they may be
        // dropped -- the silent controller-side drop delivers no deviceDisconnected). The reconciler polls
        // dev.getConnected() each tick and is the single source of truth; an event just makes it react sooner.
        @Override
        public void deviceConnected(BTDevice device, boolean discovered, long timestamp) {
            requeueReconcile();
        }

        @Override
        public void deviceReady(BTDevice device, long timestamp) {
            requeueReconcile();
        }

        @Override
        public void deviceDisconnected(BTDevice device, HCIStatusCode reason, short handle, long timestamp) {
            requeueReconcile();
        }
    }

    /**
     * Receives adapter add/remove from the manager. {@code adapterAdded} hands us a fully-wired adapter,
     * which is the only safe way to obtain one (see {@link #initializeDirectBT()}).
     */
    @NonNullByDefault({})
    private class DirectBTChangedAdapterSetListener implements ChangedAdapterSetListener {
        @Override
        public void adapterAdded(BTAdapter added) {
            onAdapterAdded(added);
        }

        @Override
        public void adapterRemoved(BTAdapter removed) {
            onAdapterRemoved(removed);
        }
    }

    private final DirectBTManagerFactory managerFactory;

    public DirectBTBridgeHandler(Bridge bridge, DirectBTManagerFactory managerFactory) {
        super(bridge);
        this.managerFactory = managerFactory;
    }

    @Override
    public void initialize() {
        super.initialize();
        DirectBTAdapterConfiguration config = getConfigAs(DirectBTAdapterConfiguration.class);
        String addr = config.address;
        if (addr == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "address not set");
            return;
        }
        this.adapterAddress = new BluetoothAddress(addr.toUpperCase());
        updateStatus(ThingStatus.UNKNOWN, ThingStatusDetail.NONE, "Initializing");
        // Native load + Direct-BT init can block; do it off the main thread, then retry until the adapter
        // is present (e.g. dongle plugged in later).
        initJob = scheduler.scheduleWithFixedDelay(this::initializeDirectBT, 0, 10, TimeUnit.SECONDS);
    }

    /**
     * Ensures the Direct-BT manager is up and our {@link ChangedAdapterSetListener} is registered. The
     * actual per-adapter bring-up happens in {@link #onAdapterAdded(BTAdapter)} — that callback hands us a
     * fully-wired {@link BTAdapter}, which is required: operating on an adapter obtained any other way (e.g.
     * polling {@code getAdapters()}) crashes the native layer with a null-reference, because its native peer
     * is only associated through the manager's adapter-set lifecycle.
     */
    private synchronized void initializeDirectBT() {
        if (managerReady) {
            // Manager already up; this recurring tick only serves to (re)acquire the adapter if it was not present
            // yet (dongle plugged in later). Discovery + connection are owned entirely by the reconcile driver,
            // which is started from bringUpAdapter() once the adapter is up.
            return;
        }
        try {
            // The BTManager singleton (and the native libraries it loads) is owned by the long-lived
            // DirectBTManagerFactory DS component / the Direct-BT lib bundle, NOT by this handler. We only
            // acquire a reference here. It may not be ready yet (native still loading, missing caps, no adapter
            // present) -> null; the recurring 10s job keeps retrying until it is.
            BTManager mgr = managerFactory.getManager();
            if (mgr == null) {
                logger.debug("Direct-BT manager not ready yet; will retry");
                return;
            }
            this.manager = mgr;
            // Registering the listener immediately invokes adapterAdded() for adapters already present.
            mgr.addChangedAdapterSetListener(changedAdapterSetListener);
            managerReady = true;
            // Job intentionally NOT cancelled: it keeps retrying adapter acquisition if not present yet.
        } catch (Exception e) {
            logger.debug("Direct-BT initialization failed, will retry", e);
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, "Direct-BT init failed");
        }
    }

    /** Brings up the matching adapter: initialize (claim HCI user channel + power on) + scan. */
    private synchronized void onAdapterAdded(BTAdapter added) {
        BluetoothAddress wanted = adapterAddress;
        if (disposed || wanted == null) {
            return;
        }
        String mac = added.getAddressAndType().address.toString().toUpperCase();
        if (!mac.equals(wanted.toString())) {
            logger.trace("Direct-BT ignoring adapter {}; bridge wants {}", mac, wanted);
            return; // not our adapter
        }
        if (adapter != null) {
            return;
        }
        bringUpAdapter(added, wanted);
    }

    // ============================================================================================
    // Level-triggered reconciler driver. See docs/directbt-reconciler-design.md.
    // The reconcilers (adapter, discovery, per-device) are the ONLY things that drive the radio; no timer or
    // event ever issues start/stopDiscovery or connectLE directly. Events merely requeue a tick.
    // ============================================================================================

    /** Start (idempotently) the single-threaded reconcile driver once the adapter is up. */
    private void startReconciler() {
        synchronized (reconcileLock) {
            if (adapterReconciler == null) {
                adapterReconciler = new AdapterReconciler(logger, () -> adapter, resetBudget);
            }
            if (reconcileJob == null) {
                reconcileJob = scheduler.scheduleWithFixedDelay(this::reconcileTick, 0, RECONCILE_INTERVAL_MS,
                        TimeUnit.MILLISECONDS);
            }
        }
    }

    /**
     * One driver tick: adapter-power -> (gate) -> devices -> adapter-scan. Single-threaded via reconcileLock.
     * Scan is a field of the adapter resource (not a separate reconciler), but it reconciles AFTER the devices
     * because its desired state ({@link #isScanWanted()}) is a rollup of device state.
     */
    private void reconcileTick() {
        // CRITICAL: this runs on a scheduleWithFixedDelay job. If the body throws an UNCAUGHT exception the
        // executor SILENTLY CANCELS the repeating job -> the reconciler goes permanently dormant (device stuck,
        // no retries, only an openHAB restart recovers). The reconcilers call into the native Direct-BT stack,
        // which can throw (RuntimeException from JNI, LinkageError on a native mishap). So the WHOLE tick body is
        // wrapped: one bad tick is logged and the next 2s tick just tries again. (Regression note: this guard was
        // present historically, lost in the reconciler refactor, and its absence caused the 212 reconciler to die
        // one tick after bring-up.)
        try {
            synchronized (reconcileLock) {
                if (disposed) {
                    return;
                }
                // Any queued event-driven tick is now being serviced by THIS run (which observes the latest native
                // truth), so clear the pending flag BEFORE doing work: an event arriving during this tick will then
                // schedule a fresh follow-up rather than being lost.
                reconcilePending.set(false);
                lastTickAt = System.currentTimeMillis();
                AdapterReconciler ar = adapterReconciler;
                if (ar == null) {
                    return;
                }
                boolean adapterOk = ar.reconcile(); // power phase (the prerequisite gate)
                if (!adapterOk) {
                    // Prerequisite not in sync: pause dependents (freeze their timers) so a long adapter outage does
                    // not age devices toward escalation (which would storm on recovery). The scan field is reset so
                    // a stale "scan off" can't let a connect fire against an un-powered adapter.
                    ar.resetScanState();
                    forEachDevice(d -> d.getReconciler().pause());
                    return;
                }
                // Devices first (they update connected/connecting state that the scan's desired is computed from),
                // then the adapter scan field. The flag-sync sub-step inside a device runs even right after a reset.
                forEachDevice(d -> {
                    DeviceReconciler rec = d.getReconciler();
                    rec.unpause();
                    rec.reconcile();
                });
                ar.reconcileScan(isScanWanted()); // scan phase (the adapter's second field)
            }
        } catch (RuntimeException | LinkageError e) {
            // Never let a single tick's failure cancel the repeating reconcile job.
            logger.warn("Direct-BT reconcile tick failed; will retry next tick", e);
        }
    }

    /**
     * Request a reconcile tick (event hint). Safe to call from native callback threads. COALESCES: a burst of
     * events collapses to a single queued tick (the surplus do zero native reads), and event-driven ticks are
     * floored to {@link #MIN_OBSERVE_INTERVAL_MS} so an event storm cannot drive expensive native polling faster
     * than that. The periodic job is the backstop, so even a fully-throttled burst is reconciled within one
     * interval. Events may make a tick happen SOONER than the periodic interval, never MORE OFTEN than the floor.
     */
    void requeueReconcile() {
        if (disposed) {
            return;
        }
        if (!reconcilePending.compareAndSet(false, true)) {
            return; // a tick is already queued/in-flight; it will observe the latest truth. No native read here.
        }
        long sinceLast = System.currentTimeMillis() - lastTickAt;
        long delay = Math.max(0, MIN_OBSERVE_INTERVAL_MS - sinceLast);
        // Hop onto the scheduler so we never block a native callback thread on the reconcileLock / native calls.
        scheduler.schedule(this::reconcileTick, delay, TimeUnit.MILLISECONDS);
    }

    /** @return the adapter's reset budget (shared by reconcilers + device-requested resets). */
    public ResetBudget getResetBudget() {
        return resetBudget;
    }

    /** @return the adapter reconciler (devices query its observed scan state via isScanOff() to gate connectLE). */
    @Nullable
    AdapterReconciler getAdapterReconciler() {
        return adapterReconciler;
    }

    /**
     * Desired discovery state, computed from device state:
     * <ul>
     * <li>scan ON iff some wanted device still needs to be <em>discovered</em>
     * ({@link DeviceReconciler#wantsDiscovery()}
     * — no native handle yet: the cold-start bootstrap, and the re-find after a drop clears the handle), AND</li>
     * <li>no device is currently <em>connecting</em> — the controller rejects create-connection while scanning, so
     * the device establishing a link needs the scan off; we hand the radio to it, then resume scanning.</li>
     * </ul>
     * A device that already has a native handle but isn't connected does NOT keep the scan on: it wants the scan
     * OFF so {@link DeviceReconciler} can issue connectLE. That is the handoff that breaks the discover/connect
     * deadlock — the scan runs only until the device is found, then stops so the connect can fire.
     * <p>
     * Note we do NOT suppress the scan merely because another device is already connected: with multiple wanted
     * devices on one adapter, a still-undiscovered device would otherwise never be found (the first to connect would
     * pin the scan off forever). Direct-BT's {@link DiscoveryPolicy#PAUSE_CONNECTED_UNTIL_READY} discovery policy
     * exists precisely to keep connected devices stable while a scan runs, so scanning to find device B while
     * device A holds a live ACL is safe. Only an in-flight create-connection blocks the scan.
     */
    private boolean isScanWanted() {
        boolean[] needsDiscovery = { false };
        boolean[] connecting = { false };
        forEachDevice(d -> {
            DeviceReconciler rec = d.getReconciler();
            if (rec.wantsDiscovery()) {
                needsDiscovery[0] = true;
            }
            DeviceReconciler.Observed o = rec.lastObserved();
            if (o != null && o.flagConnecting) {
                connecting[0] = true;
            }
        });
        return needsDiscovery[0] && !connecting[0];
    }

    /**
     * Reset the adapter on a device's behalf (wedged create-connection / COMMAND_DISALLOWED). The caller
     * (DeviceReconciler) has already consumed the shared reset budget via its own {@code tryReset(name)} before
     * invoking this, so do not gate on {@code tryReset} again here.
     */
    void requestAdapterReset() {
        BTAdapter a = adapter;
        if (a != null) {
            HCIStatusCode rc = a.reset();
            logger.warn("Direct-BT adapter reset on device request -> {} (powered={})", rc, a.isPowered());
            if (rc == HCIStatusCode.SUCCESS && !a.isPowered()) {
                a.setPowered(true);
            }
        }
    }

    private void bringUpAdapter(BTAdapter added, BluetoothAddress wanted) {
        try {
            logger.debug("Direct-BT onAdapterAdded {} pre-state: initialized={} powered={}", wanted,
                    added.isInitialized(), added.isPowered());
            // Bring the adapter to an initialized + powered state FIRST. Three cases:
            // - never initialized -> initialize(), then power on if needed
            // - initialized but off -> reset() (some controllers do not recover from setPowered(true) alone)
            // - already powered -> nothing to do
            // Order matters: addStatusListener() / startDiscovery() must come AFTER the adapter is
            // initialized & powered. Calling addStatusListener() on a freshly-replayed, not-yet-initialized
            // adapter crashes the native layer with a null-reference (jaulib helper_jni.hpp:512).
            if (!added.isInitialized()) {
                // Power the adapter ON as part of initialize (BTMode.DUAL, true). Splitting init from power
                // (initialize(...,false) then a separate reset()) stalled bring-up on some CSR controllers
                // (observed on the CSR8510 A10 / bcdDevice 88.91 on 212: initialize left powered=false and the
                // follow-up added.reset() hung, so the reconciler never started and the bridge sat UNKNOWN forever).
                HCIStatusCode rc = added.initialize(BTMode.DUAL, true);
                logger.debug("Direct-BT adapter {} initialize: {} (powered={} initialized={})", wanted, rc,
                        added.isPowered(), added.isInitialized());
                if (rc != HCIStatusCode.SUCCESS) {
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                            "Adapter initialization failed: " + rc + " (is bluetoothd disabled for this adapter?)");
                    return;
                }
            }
            if (!added.isPowered()) {
                // Already initialized but off (or initialize did not power it): try setPowered() first, and only
                // fall back to a full reset() if that does not take. Avoid resetting unconditionally — a blind
                // reset() can hang/wedge some CSR controllers.
                if (!added.setPowered(true)) {
                    HCIStatusCode rc = added.reset();
                    logger.debug("Direct-BT adapter {} reset: {} (powered={} initialized={})", wanted, rc,
                            added.isPowered(), added.isInitialized());
                    if (rc == HCIStatusCode.SUCCESS && !added.isPowered()) {
                        added.setPowered(true);
                    }
                    if (rc != HCIStatusCode.SUCCESS) {
                        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                                "Adapter power-up failed: " + rc + " (is bluetoothd disabled for this adapter?)");
                        return;
                    }
                }
            }
            // Power-on may be asynchronous; wait (bounded) for the controller to report POWERED before
            // starting discovery, otherwise startDiscovery() fails with NOT_POWERED.
            for (int i = 0; i < POWER_ON_WAIT_TRIES && !added.isPowered(); i++) {
                Thread.sleep(POWER_ON_WAIT_MS);
            }
            if (!added.isPowered()) {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                        "Adapter did not power on (is bluetoothd disabled for this adapter?)");
                return;
            }
            // The power-wait may have slept while dispose() ran; bail before mutating native state.
            if (disposed) {
                return;
            }
            // Adapter is initialized & powered: now safe to create + attach the status listener. A FRESH
            // listener is created per bring-up (not cached/reused across adapter objects), and MUST be
            // created here (after BTFactory init) so its native peer is built.
            AdapterStatusListener listener = new DirectBTStatusListener();
            if (!listener.isValid()) {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                        "Status listener native peer invalid");
                return;
            }
            if (!added.addStatusListener(listener)) {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                        "Failed to register status listener");
                return;
            }
            statusListener = listener;
            // Clear any prior discovery state first: startDiscovery() returns INTERNAL_FAILURE if the adapter
            // is already discovering (e.g. from a previous handler instance). stopDiscovery() is a safe no-op
            // otherwise. We do NOT start discovery here -- the level-triggered reconciler owns discovery on/off
            // and will turn the scan on at its first tick because no wanted device is connected yet.
            added.stopDiscovery();
            this.adapter = added;
            updateStatus(ThingStatus.ONLINE);
            startReconciler();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            logger.debug("Failed to bring up Direct-BT adapter {}", wanted, e);
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, "Adapter bring-up failed");
        }
    }

    private synchronized void onAdapterRemoved(BTAdapter removed) {
        if (adapter == removed) {
            forEachDevice(DirectBTBluetoothDevice::close);
            detachAdapter();
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, "Adapter removed");
        }
    }

    /**
     * Best-effort detach from the current adapter: remove our status listener, stop discovery, and clear
     * the cached adapter + listener so a later re-add gets a fresh listener. Caller must hold the monitor.
     */
    private void detachAdapter() {
        BTAdapter localAdapter = adapter;
        AdapterStatusListener localListener = statusListener;
        if (localAdapter != null) {
            try {
                if (localListener != null) {
                    localAdapter.removeStatusListener(localListener);
                }
                localAdapter.stopDiscovery();
            } catch (RuntimeException e) {
                logger.debug("Error detaching Direct-BT adapter", e);
            }
        }
        adapter = null;
        statusListener = null;
    }

    private @Nullable DirectBTBluetoothDevice onDeviceFound(BTDevice btDevice) {
        if (disposed) {
            return null;
        }
        DirectBTBluetoothDevice device = getDevice(toAddress(btDevice));
        device.updateBTDevice(btDevice);
        deviceDiscovered(device);
        return device;
    }

    boolean isDeviceEnabled(BluetoothAddress address) {
        String addrStr = address.toString();
        for (Thing childThing : getThing().getThings()) {
            Object childAddr = childThing.getConfiguration().get(BluetoothBindingConstants.CONFIGURATION_ADDRESS);
            if (addrStr.equalsIgnoreCase(String.valueOf(childAddr))) {
                return childThing.isEnabled();
            }
        }
        return true;
    }

    /** Resolve the openHAB device for a Direct-BT device by address, or {@code null} if disposed. */
    private @Nullable DirectBTBluetoothDevice findDevice(BTDevice btDevice) {
        if (disposed) {
            return null;
        }
        return getDevice(toAddress(btDevice));
    }

    private static BluetoothAddress toAddress(BTDevice btDevice) {
        return new BluetoothAddress(btDevice.getAddressAndType().address.toString().toUpperCase());
    }

    /** Pool for the device's blocking GATT operations. */
    ExecutorService getExecutor() {
        return executor;
    }

    @Override
    protected DirectBTBluetoothDevice createDevice(BluetoothAddress address) {
        return new DirectBTBluetoothDevice(this, address);
    }

    @Override
    public @Nullable BluetoothAddress getAddress() {
        return adapterAddress;
    }

    @Override
    public void dispose() {
        // Set the disposed flag first so native callbacks (deviceFound) and any in-flight bring-up bail
        // out, then take the monitor to clean up without racing onAdapterAdded/onDeviceFound.
        disposed = true;
        cancelInitJob();
        synchronized (reconcileLock) {
            ScheduledFuture<?> rec = reconcileJob;
            if (rec != null) {
                rec.cancel(false);
                reconcileJob = null;
            }
        }
        synchronized (this) {
            BTManager localManager = manager;
            if (localManager != null) {
                try {
                    localManager.removeChangedAdapterSetListener(changedAdapterSetListener);
                } catch (RuntimeException e) {
                    logger.debug("Error removing Direct-BT adapter-set listener on dispose", e);
                }
            }
            // BEST-EFFORT, TIME-BOXED native teardown. Cleanly disconnecting every device (close() ->
            // dev.disconnect()) before detaching the adapter is the right thing for a hot redeploy (avoids
            // leaving a half-open link the controller must time out, and resets the openHAB model so a re-bound
            // handler re-discovers). BUT the native dev.disconnect() can BLOCK INDEFINITELY: it joins the
            // Direct-BT L2CAP reader thread (BTGattHandler::disconnect -> l2cap_reader_service.join()), and on a
            // wedged controller / unresponsive socket that read never returns. Running it inline on dispose()
            // hangs the whole JVM on shutdown -> karaf 'stop' never completes -> systemd SIGKILLs openHAB and
            // leaves the service 'failed' (observed on the production NUC). openHAB gives no reliable
            // "this is a full JVM shutdown vs a redeploy" signal at dispose() time, so we do NOT try to detect
            // shutdown; instead we cap the blocking work with a timeout. If it doesn't finish, we abandon it:
            // on a real shutdown the OS reclaims the sockets/threads anyway, and on a redeploy only a genuinely
            // wedged device is skipped (which is exactly the case where blocking would hang us).
            runTimeBoxed("device close + adapter detach", DISPOSE_NATIVE_TIMEOUT_MS, () -> {
                forEachDevice(DirectBTBluetoothDevice::close);
                detachAdapter();
            });
            managerReady = false;
        }
        super.dispose();
    }

    private void cancelInitJob() {
        ScheduledFuture<?> job = initJob;
        if (job != null) {
            job.cancel(true);
            initJob = null;
        }
    }

    /**
     * Run a potentially-blocking native cleanup action on a throwaway daemon thread and wait at most
     * {@code timeoutMs} for it. If it does not finish in time we ABANDON it (the thread is a daemon, so it does
     * not keep the JVM alive) and return — the caller must treat the work as best-effort. Used for dispose()'s
     * native teardown, which can block indefinitely joining the Direct-BT L2CAP reader thread on a wedged radio.
     */
    private void runTimeBoxed(String what, long timeoutMs, Runnable action) {
        Thread worker = new Thread(() -> {
            try {
                action.run();
            } catch (RuntimeException | LinkageError e) {
                logger.debug("Direct-BT dispose: {} threw", what, e);
            }
        }, "directbt-dispose-" + getThing().getUID().getId());
        worker.setDaemon(true);
        worker.start();
        try {
            worker.join(timeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (worker.isAlive()) {
            logger.warn(
                    "Direct-BT dispose: {} did not finish within {}ms; abandoning (best-effort). The native "
                            + "disconnect is likely blocked on a wedged controller; the JVM/OS will reclaim it.",
                    what, timeoutMs);
        }
    }
}
