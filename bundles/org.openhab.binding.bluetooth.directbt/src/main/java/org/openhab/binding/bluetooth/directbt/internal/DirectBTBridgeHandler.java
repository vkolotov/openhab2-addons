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

import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.direct_bt.AdapterStatusListener;
import org.direct_bt.BTAdapter;
import org.direct_bt.BTDevice;
import org.direct_bt.BTManager;
import org.direct_bt.BTManager.ChangedAdapterSetListener;
import org.direct_bt.BTMode;
import org.direct_bt.DiscoveryPolicy;
import org.direct_bt.EIRDataTypeSet;
import org.direct_bt.GattCacheMode;
import org.direct_bt.HCIStatusCode;
import org.direct_bt.PairingMode;
import org.direct_bt.SMPPairingState;
import org.direct_bt.ScanType;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.bluetooth.AbstractBluetoothBridgeHandler;
import org.openhab.binding.bluetooth.BluetoothAddress;
import org.openhab.binding.bluetooth.BluetoothBindingConstants;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.AdapterReconciler;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.DeviceReconciler;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.ResetBudget;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.adapter.AdapterLeaseCoordinator;
import org.openhab.core.OpenHAB;
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

    // Separate pool for openHAB notification fanout, so delivering CHARACTERISTIC_UPDATED events is never
    // queued behind the blocking GATT calls above (a readValue can hold a pool thread for up to 12 s).
    private final ExecutorService notifyExecutor = ThreadPoolManager.getPool("bluetooth-directbt-notify");

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
    // Whether to scan continuously to surface NEW (unconfigured) devices to the inbox, even when no configured
    // device needs discovering. Read from the bridge Thing config; a manual scan (activeScanEnabled) forces it on
    // regardless.
    private volatile boolean backgroundDiscovery;
    private volatile int scanIntervalSlots = AdapterReconciler.DEFAULT_LE_SCAN_INTERVAL;
    private volatile int scanWindowSlots = AdapterReconciler.DEFAULT_LE_SCAN_WINDOW;
    private volatile int connectionIntervalSlots = 24;
    private volatile int connectionSupervisionTimeoutSlots = 600;
    private volatile boolean scanDuplicateFilter;
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
    // Adapter reset fanout (constraint 12): monotonic generation stamped on every reset announcement, and the
    // completion handed from the async reset thread to the reconcile tick that announces it to the devices.
    private final AtomicLong adapterResetGeneration = new AtomicLong();
    private final AtomicBoolean adapterResetInFlight = new AtomicBoolean();
    private final AtomicReference<@Nullable AdapterResetCompletion> pendingAdapterResetCompletion = new AtomicReference<>();
    // When the last reconcile tick (periodic or event-driven) last ran, for the MIN_OBSERVE_INTERVAL_MS floor.
    private volatile long lastTickAt;
    private final ResetBudget resetBudget = new ResetBudget();

    // Radio arbitration: time-slices the scan/connect contention and escalates hunted-but-invisible devices
    // (sweep, then budgeted adapter reset). Only touched on the reconcile tick thread.
    private final AdapterLeaseCoordinator leaseCoordinator;
    private @Nullable AdapterReconciler adapterReconciler;
    private boolean managerReady;
    private volatile boolean disposed;
    // Per-adapter persisted-bond store (SMPKeyBin files); created once the adapter MAC is confirmed.
    private volatile @Nullable BondStore bondStore;

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
            BluetoothAddress address = toAddress(device);
            logger.debug("Direct-BT deviceFound: {}", address);
            DirectBTBluetoothDevice btDevice = getDevice(address);
            boolean wanted = btDevice.isWanted();
            enqueueDeviceSeen(btDevice, device, true);
            // Direct-BT's return value is ownership, not discovery flow-control: false removes the native device
            // from the shared list and the BTDevice can no longer be used for a later connect. Keep only devices
            // with active connection intent; background advertisements can stay cheap/non-persistent.
            return wanted;
        }

        @Override
        public void deviceUpdated(BTDevice device, EIRDataTypeSet updateMask, long timestamp) {
            BluetoothAddress address = toAddress(device);
            logger.trace("Direct-BT deviceUpdated: {}", address);
            enqueueDeviceSeen(address, device, false);
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
        // dev.getConnected() each tick and is the single source of truth; an event just makes it react sooner —
        // including through the device reconciler's act-backoff, which would otherwise sit on fresh evidence
        // for up to its 8 s cap (the requeued tick ran but returned at "backing off").
        @Override
        public void deviceConnected(BTDevice device, boolean discovered, long timestamp) {
            enqueueConnectionHint(toAddress(device));
        }

        @Override
        public void deviceReady(BTDevice device, long timestamp) {
            enqueueConnectionHint(toAddress(device));
        }

        @Override
        public void deviceDisconnected(BTDevice device, HCIStatusCode reason, short handle, long timestamp) {
            // Record and log why the link dropped so it is not lost; the core status otherwise shows only a bare
            // "communication error". The reconciler still drives the actual reconnect via requeueReconcile().
            BluetoothAddress address = toAddress(device);
            logger.debug("Direct-BT deviceDisconnected: {} reason={}", address, reason);
            scheduler.execute(() -> {
                DirectBTBluetoothDevice ohDevice = getDevice(address);
                ohDevice.setDisconnectReason(String.valueOf(reason));
                ohDevice.noteNativeDisconnectEvent();
                ohDevice.getReconciler().expediteNextAct(); // act on the failure now, not after the act-backoff
                requeueReconcile();
            });
        }

        @Override
        public void devicePairingState(BTDevice device, SMPPairingState state, PairingMode mode, long timestamp) {
            // Hint only: an SMP negotiation just changed state. Reconciling now (rather than waiting up to a full
            // tick) lets the device reconciler observe the negotiating window promptly and freeze its connect
            // deadline, so a short-lived pairing phase between 2s ticks can't slip past and trip the stuck-connect
            // teardown. The reconciler still polls getPairingState() as the source of truth.
            BluetoothAddress deviceAddress = toAddress(device);
            logger.debug("Direct-BT devicePairingState: {} state={} mode={}", deviceAddress, state, mode);
            if (state == SMPPairingState.PASSKEY_EXPECTED) {
                // PASSKEY_EXPECTED is the one adapter-status callback path that deliberately replies inline:
                // Direct-BT requires the passkey while the pairing transaction is active. The heavier persistence
                // and reconcile hinting below are still hopped off the native callback thread.
                replyPasskey(device);
            }
            enqueuePairingState(deviceAddress, device, state, mode);
        }
    }

    private void enqueueDeviceSeen(DirectBTBluetoothDevice device, BTDevice btDevice, boolean requeueAfterUpdate) {
        scheduler.execute(() -> processDeviceSeen(device, btDevice, requeueAfterUpdate));
    }

    private void enqueueDeviceSeen(BluetoothAddress address, BTDevice btDevice, boolean requeueAfterUpdate) {
        scheduler.execute(() -> processDeviceSeen(address, btDevice, requeueAfterUpdate));
    }

    private void processDeviceSeen(BluetoothAddress address, BTDevice btDevice, boolean requeueAfterUpdate) {
        processDeviceSeen(getDevice(address), btDevice, requeueAfterUpdate);
    }

    private void processDeviceSeen(DirectBTBluetoothDevice device, BTDevice btDevice, boolean requeueAfterUpdate) {
        try {
            handleDeviceFound(device, btDevice);
            if (requeueAfterUpdate) {
                requeueReconcile(); // hint: a wanted device may now be connectable
            }
        } catch (RuntimeException | LinkageError e) {
            logger.debug("Direct-BT deviceSeen processing failed off callback thread", e);
        }
    }

    private void enqueuePairingState(BluetoothAddress deviceAddress, BTDevice device, SMPPairingState state,
            PairingMode mode) {
        scheduler.execute(() -> processPairingState(deviceAddress, device, state, mode));
    }

    private void processPairingState(BluetoothAddress deviceAddress, BTDevice device, SMPPairingState state,
            PairingMode mode) {
        try {
            if (state == SMPPairingState.COMPLETED && mode != PairingMode.PRE_PAIRED) {
                // A FRESH pairing just distributed keys: persist them so the bond survives a restart/power
                // cycle. PRE_PAIRED completions reuse existing keys (nothing new to save). Only for devices
                // that opted into a security mode; runs on the executor because config lookup, native key reads,
                // and file I/O do not belong on the native callback thread.
                BondStore store = bondStore;
                if (store != null) {
                    executor.submit(() -> {
                        if (!BluetoothBindingConstants.CONNECTION_SECURITY_NONE
                                .equalsIgnoreCase(getDeviceConnectionSecurity(deviceAddress))) {
                            store.save(device);
                        }
                    });
                }
            }
            requeueReconcile();
        } catch (RuntimeException | LinkageError e) {
            logger.debug("Direct-BT pairing-state processing failed off callback thread for {}", deviceAddress, e);
        }
    }

    private void enqueueConnectionHint(BluetoothAddress address) {
        scheduler.execute(() -> {
            getDevice(address).getReconciler().expediteNextAct();
            requeueReconcile();
        });
    }

    /**
     * The device is asking us to supply the passkey for authenticated (PASSKEY_ENTRY) pairing. Reply with the
     * per-device configured PIN if one is set, otherwise decline so the negotiation fails cleanly rather than
     * hanging. Must be called from the {@code PASSKEY_EXPECTED} state (Direct-BT rejects it otherwise).
     */
    void replyPasskey(BTDevice device) {
        BluetoothAddress address = toAddress(device);
        int passkey = getDevicePasskey(address);
        try {
            if (passkey >= 0) {
                logger.debug("Direct-BT PASSKEY_EXPECTED for {}; supplying configured passkey", address);
                device.setPairingPasskey(passkey);
            } else {
                logger.warn("Direct-BT PASSKEY_EXPECTED for {} but no passkey configured; declining pairing", address);
                device.setPairingPasskeyNegative();
            }
        } catch (RuntimeException e) {
            logger.debug("Direct-BT passkey reply for {} threw", address, e);
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
            scheduler.execute(() -> onAdapterAdded(added));
        }

        @Override
        public void adapterRemoved(BTAdapter removed) {
            scheduler.execute(() -> onAdapterRemoved(removed));
        }
    }

    private final DirectBTManagerFactory managerFactory;
    // Clock behind the reconcile-tick spacing (injectable for tests).
    private final Clock clock;

    public DirectBTBridgeHandler(Bridge bridge, DirectBTManagerFactory managerFactory) {
        this(bridge, managerFactory, Clock.systemUTC());
    }

    /** Test constructor: inject the clock driving the reconcile-tick spacing. */
    DirectBTBridgeHandler(Bridge bridge, DirectBTManagerFactory managerFactory, Clock clock) {
        super(bridge);
        this.managerFactory = managerFactory;
        this.clock = clock;
        this.leaseCoordinator = new AdapterLeaseCoordinator(logger, resetBudget, this::recoverySweep,
                this::requestAdapterReset, clock);
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
        this.adapterAddress = new BluetoothAddress(addr.toUpperCase(Locale.ROOT));
        this.backgroundDiscovery = config.backgroundDiscovery;
        this.scanIntervalSlots = config.scanIntervalSlots;
        this.scanWindowSlots = config.scanWindowSlots;
        this.connectionIntervalSlots = clamp(config.connectionIntervalSlots, 6, 3200);
        this.connectionSupervisionTimeoutSlots = clamp(config.connectionSupervisionTimeoutSlots,
                Math.max(10, this.connectionIntervalSlots / 4 + 1), 3200);
        this.scanDuplicateFilter = config.scanDuplicateFilter;
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
            // Manager already up; adapter (re)acquisition is driven by the ChangedAdapterSetListener callbacks
            // (adapterAdded fires when a dongle is plugged in later), so this retry job has nothing left to do.
            cancelInitJob();
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
            // The retry job cancels itself on its next tick (see above); adapterAdded takes over from here.
        } catch (Exception e) {
            logger.debug("Direct-BT initialization failed, will retry", e);
            String msg = e.getMessage();
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    "Direct-BT init failed" + (msg != null ? ": " + msg : ""));
        }
    }

    /** Brings up the matching adapter: initialize (claim HCI user channel + power on) + scan. */
    private synchronized void onAdapterAdded(BTAdapter added) {
        BluetoothAddress wanted = adapterAddress;
        if (disposed || wanted == null) {
            return;
        }
        String mac = added.getAddressAndType().address.toString().toUpperCase(Locale.ROOT);
        if (!mac.equals(wanted.toString())) {
            logger.trace("Direct-BT ignoring adapter {}; bridge wants {}", mac, wanted);
            return; // not our adapter
        }
        if (adapter != null) {
            return;
        }
        // Bond persistence is per adapter (the LTK binds the peer to THIS adapter's identity), so the store
        // directory is keyed by the adapter MAC. Created here because the MAC is only certain once the
        // adapter is up.
        bondStore = new BondStore(Path.of(OpenHAB.getUserDataFolder(), "bluetooth", "directbt-keys",
                mac.replace(":", "").toLowerCase(Locale.ROOT)));
        bringUpAdapter(added, wanted);
    }

    /** @return the per-adapter persisted-bond store, or {@code null} until the adapter is up. */
    @Nullable
    BondStore getBondStore() {
        return bondStore;
    }

    short getConnectionIntervalSlots() {
        return (short) connectionIntervalSlots;
    }

    short getConnectionSupervisionTimeoutSlots() {
        return (short) connectionSupervisionTimeoutSlots;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    // ============================================================================================
    // Level-triggered reconciler driver.
    // The reconcilers (adapter, discovery, per-device) are the ONLY things that drive the radio; no timer or
    // event ever issues start/stopDiscovery or connectLE directly. Events merely requeue a tick.
    // ============================================================================================

    /** Start (idempotently) the single-threaded reconcile driver once the adapter is up. */
    private void startReconciler() {
        synchronized (reconcileLock) {
            if (adapterReconciler == null) {
                AdapterReconciler ar = new AdapterReconciler(logger, () -> adapter, resetBudget);
                ar.setScanParameters(scanIntervalSlots, scanWindowSlots, scanDuplicateFilter);
                adapterReconciler = ar;
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
                lastTickAt = clock.millis();
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
                // Announce any completed adapter reset before the devices act on it: their actors must be
                // re-parked by intent (post-reset generation) before this tick's reconcile drives recovery.
                fanOutPendingAdapterResetCompletion();
                // Devices first (they update connected/connecting state that the scan's desired is computed from),
                // then the adapter scan field. The flag-sync sub-step inside a device runs even right after a reset.
                adoptOrphanNativeConnections();
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
        long sinceLast = clock.millis() - lastTickAt;
        long delay = Math.max(0, MIN_OBSERVE_INTERVAL_MS - sinceLast);
        // Hop onto the scheduler so we never block a native callback thread on the reconcileLock / native calls.
        scheduler.schedule(this::reconcileTick, delay, TimeUnit.MILLISECONDS);
    }

    /**
     * LEVEL-TRIGGERED ORPHAN ADOPTION (reconcile driver only, once per tick, before the device phase).
     *
     * A create-connection can complete AFTER the device reconciler gave up on it and dropped the wrapper's
     * native handle: clearing-pending nulls the handle, but the native {@code BTDevice.disconnect()} no-ops on
     * a not-yet-established connect, so the controller's pending {@code le_create_conn} stays armed and the
     * link can land seconds later. Without adoption that late link is an ORPHAN ACL: the wrapper observes
     * "not connected", the connected peripheral stops advertising, and rediscovery can never re-attach — a
     * permanent wedge (observed live).
     *
     * Per the reconciler pattern this is an OBSERVE-side repair, not an event reaction: deviceConnected stays
     * a pure requeue hint (events may be dropped), and each tick re-checks the level — "a wanted wrapper has
     * no native handle, yet the adapter still tracks that device as connected" — and re-attaches the handle.
     * Gated so the native sweep runs only when some wanted wrapper is actually handle-less; in steady state
     * this adds zero native reads. Only a CONNECTED native device is adopted — a disconnected one must come
     * back through a fresh advertisement (deviceFound), preserving the "trust the fresh frame" discipline.
     */
    private void adoptOrphanNativeConnections() {
        List<DirectBTBluetoothDevice> orphans = new ArrayList<>();
        forEachDevice(d -> {
            // adoptionAllowed(): skip devices inside the post-teardown quiet window — their native disconnect is
            // still completing and re-adopting the dying link would undo the deliberate bounce.
            if (d.isWanted() && !d.hasNativeDevice() && d.adoptionAllowed()) {
                orphans.add(d);
            }
        });
        if (orphans.isEmpty()) {
            return;
        }
        BTAdapter localAdapter = adapter;
        if (localAdapter == null) {
            return;
        }
        List<BTDevice> known;
        try {
            known = localAdapter.getDiscoveredDevices();
        } catch (RuntimeException e) {
            logger.debug("Direct-BT getDiscoveredDevices failed during orphan adoption", e);
            return;
        }
        adoptMatchingOrphans(orphans, known, logger);
    }

    /** The adoption matcher: re-attach each orphan to a CONNECTED native device with its address, if one exists. */
    static void adoptMatchingOrphans(List<DirectBTBluetoothDevice> orphans, List<BTDevice> known, Logger logger) {
        for (DirectBTBluetoothDevice orphan : orphans) {
            String addr = orphan.getAddress().toString();
            for (BTDevice candidate : known) {
                try {
                    if (addr.equalsIgnoreCase(candidate.getAddressAndType().address.toString())
                            && candidate.getConnected()) {
                        logger.debug("Adopting connected native device for {} (orphaned ACL; re-attaching handle)",
                                addr);
                        orphan.updateBTDevice(candidate);
                        break;
                    }
                } catch (RuntimeException e) {
                    logger.debug("Orphan-adoption candidate check failed for {}", addr, e);
                }
            }
        }
    }

    @Override
    public void scanStart() {
        // Core sets activeScanEnabled = true (manual/inbox scan). Reconcile now so isScanWanted() turns the radio
        // on promptly rather than waiting for the next periodic tick.
        super.scanStart();
        requeueReconcile();
    }

    @Override
    public void scanStop() {
        // Core clears activeScanEnabled. Reconcile so the scan winds back down to the configured desired state.
        super.scanStop();
        requeueReconcile();
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
        boolean[] establishing = { false };
        forEachDevice(d -> {
            DeviceReconciler rec = d.getReconciler();
            if (rec.wantsDiscovery()) {
                needsDiscovery[0] = true;
            }
            DeviceReconciler.Observed o = rec.lastObserved();
            if (o != null && o.flagConnecting) {
                connecting[0] = true;
            }
            // A configured device that HAS a handle but is not yet fully connected is still trying to establish
            // its link (across the connect retry/backoff gaps, not only the brief flagConnecting instant).
            // Background/inbox discovery must yield to it, else the scan restarts between attempts and the
            // controller can never complete the create-connection (observed as a connect/clear-pending flap).
            if (rec.needsConnection()) {
                establishing[0] = true;
            }
            // Connected but mid-GATT-walk: the scan steals radio slots the walk needs. Fatal on weak links —
            // the walk times out, restarts, and the device never reaches "ready", which keeps the scan on: a
            // self-sustaining starvation loop.
            if (rec.isResolvingGatt()) {
                establishing[0] = true;
            }
        });
        // The needsDiscovery-vs-establishing conflict is arbitrated by the lease coordinator (time-sliced both
        // ways). The old static rollup let discovery win indefinitely, which starved every connect for as long
        // as a hunted device stayed invisible (the 2026-07-16 2h17m outage). The coordinator also runs the
        // hunted-device starvation ladder (sweep -> budgeted adapter reset) from the same demand rollup.
        return leaseCoordinator.decide(needsDiscovery[0], backgroundDiscovery, activeScanEnabled, connecting[0],
                establishing[0]);
    }

    /**
     * Best-effort recovery sweep on the lease coordinator's behalf: disconnect every wanted-but-unestablished
     * device to clear stuck pending create-connections at the controller (the stuck-initiator variant of the
     * hunted-device failure; a controller-held zombie needs the adapter reset rung instead). Runs the blocking
     * native disconnects on the blocking-ops pool, and collects targets first so no native call ever runs while
     * holding the device-map iteration lock (the 2026-07-16 16:30 wedge lesson).
     */
    private void recoverySweep() {
        List<DirectBTBluetoothDevice> targets = new ArrayList<>();
        forEachDevice(d -> {
            DeviceReconciler rec = d.getReconciler();
            if (rec.wantsDiscovery() || rec.needsConnection()) {
                targets.add(d);
            }
        });
        if (targets.isEmpty()) {
            return;
        }
        logger.warn("Direct-BT recovery sweep: best-effort disconnect of {} wanted-but-unestablished device(s)",
                targets.size());
        executor.execute(() -> {
            for (DirectBTBluetoothDevice d : targets) {
                d.disconnectNative();
            }
            requeueReconcile();
        });
    }

    /**
     * Reset the adapter on a device/coordinator's behalf (wedged create-connection, COMMAND_DISALLOWED, or hunted
     * device recovery). The caller has already consumed the shared reset budget via its own {@code tryReset(...)}
     * before invoking this, so do not gate on {@code tryReset} again here. This method still owns the in-flight
     * guard: a hung native reset must not allow more native reset calls to stack up behind it.
     */
    void requestAdapterReset() {
        BTAdapter a = adapter;
        if (a == null) {
            return;
        }
        if (!adapterResetInFlight.compareAndSet(false, true)) {
            logger.debug("Direct-BT adapter reset requested while reset is already in flight; ignoring duplicate");
            return;
        }
        // FANOUT (frozen constraint 12): an adapter reset is a generation boundary for EVERY device on it —
        // the reset invalidates all native handles/connections at once. Announcing it BEFORE the native call
        // fences every in-flight attempt's events and effects as stale, so nothing from the pre-reset world can
        // land on the post-reset adapter. Announced on the reconcile tick thread (the actor runtime is
        // caller-threaded); the native reset itself runs async below.
        long adapterGeneration = adapterResetGeneration.incrementAndGet();
        forEachDevice(d -> d.getReconciler().onAdapterResetStarted(adapterGeneration));
        // OFF the reconcile tick, always: DBTAdapter.reset() is a native call observed to hang indefinitely
        // (2026-07-16: 5+ min inside resetImpl, wedging every reconcile and device operation behind the tick's
        // forEachDevice lock). A hung reset must cost at most one pool thread, never the reconcile loop.
        logger.warn("Direct-BT adapter reset requested (generation {}); running async", adapterGeneration);
        executor.execute(() -> {
            long started = System.nanoTime();
            String result;
            try {
                HCIStatusCode rc = a.reset();
                result = rc.name();
                logger.warn("Direct-BT adapter reset -> {} after {}ms (powered={})", rc,
                        (System.nanoTime() - started) / 1_000_000, a.isPowered());
                if (rc == HCIStatusCode.SUCCESS && !a.isPowered()) {
                    a.setPowered(true);
                }
            } catch (RuntimeException | LinkageError e) {
                result = "THREW_" + e.getClass().getSimpleName();
                logger.warn("Direct-BT adapter reset threw after {}ms", (System.nanoTime() - started) / 1_000_000, e);
            }
            // Completion fans out on the RECONCILE TICK, not this pool thread: the actor runtime is
            // caller-threaded, so the announcement must be serialized with every other actor input. Any
            // result re-parks the actors by intent (a failed reset leaves the adapter no less invalidated).
            pendingAdapterResetCompletion.set(new AdapterResetCompletion(adapterGeneration, result));
            requeueReconcile();
        });
    }

    /** One completed adapter reset awaiting fanout on the next reconcile tick. */
    private static final class AdapterResetCompletion {
        private final long generation;
        private final String result;

        AdapterResetCompletion(long generation, String result) {
            this.generation = generation;
            this.result = result;
        }
    }

    /**
     * Deliver a completed adapter reset to every device actor (see {@link #requestAdapterReset()}). Runs at the
     * top of the reconcile tick so the announcement is serialized with the actors' other inputs.
     */
    private void fanOutPendingAdapterResetCompletion() {
        AdapterResetCompletion completion = pendingAdapterResetCompletion.getAndSet(null);
        if (completion == null) {
            return;
        }
        logger.debug("Direct-BT adapter reset generation {} completed ({}); announcing to device actors",
                completion.generation, completion.result);
        try {
            forEachDevice(d -> d.getReconciler().onAdapterResetCompleted(completion.generation, completion.result));
        } finally {
            adapterResetInFlight.set(false);
        }
    }

    private void bringUpAdapter(BTAdapter added, BluetoothAddress wanted) {
        try {
            logger.debug("Direct-BT onAdapterAdded {} pre-state: initialized={} powered={}", wanted,
                    added.isInitialized(), added.isPowered());
            String powerUpError = powerUpAdapter(added, logger, POWER_ON_WAIT_TRIES, POWER_ON_WAIT_MS);
            if (powerUpError != null) {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                        powerUpError + " (is bluetoothd disabled for this adapter?)");
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
            // Include the adapter address + discovery mode in the ONLINE description: a stable, informative
            // annotation (not something that churns like RSSI), so the UI shows which controller is bound and
            // whether it is surfacing new devices to the inbox.
            updateStatus(ThingStatus.ONLINE, ThingStatusDetail.NONE, "Adapter " + wanted
                    + (backgroundDiscovery ? " (background discovery on)" : " (background discovery off)"));
            startReconciler();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            logger.debug("Failed to bring up Direct-BT adapter {}", wanted, e);
            String msg = e.getMessage();
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    "Adapter bring-up failed" + (msg != null ? ": " + msg : ""));
        }
    }

    /**
     * The EDGE-TRIGGERED adapter power-up ladder, run once when the manager hands us the adapter. Three cases:
     * <ul>
     * <li>never initialized -> {@code initialize(BTMode.DUAL, true)} — power ON as part of initialize. Splitting
     * init from power ({@code initialize(...,false)} then a separate {@code reset()}) stalled bring-up on some CSR
     * controllers (observed on the CSR8510 A10 / bcdDevice 88.91: initialize left powered=false and the follow-up
     * reset() hung, so the reconciler never started and the bridge sat UNKNOWN forever);</li>
     * <li>initialized but off -> try {@code setPowered(true)} first, and only fall back to a full {@code reset()}
     * if that does not take — a blind reset() can hang/wedge some CSR controllers;</li>
     * <li>already powered -> nothing to do.</li>
     * </ul>
     * Power-on may be asynchronous, so afterwards wait (bounded: {@code waitTries * waitMs}) for the controller to
     * report POWERED — the caller must not attach listeners or start discovery before that (addStatusListener() on
     * a not-yet-initialized adapter crashes the native layer with a null-reference, jaulib helper_jni.hpp:512).
     * <p>
     * NOTE: this ladder deliberately mirrors the LEVEL-TRIGGERED copy in {@link AdapterReconciler}'s
     * {@code act()}/{@code escalate()} (which re-runs the same decisions every tick once the driver is up). The
     * two must stay behaviourally in lockstep until they are folded together; {@code DirectBTBridgeHandlerTest}
     * locks the shared semantics down against a mocked adapter.
     *
     * @return {@code null} on success, or a human-readable failure reason for the Thing status.
     */
    static @Nullable String powerUpAdapter(BTAdapter added, Logger logger, int waitTries, long waitMs)
            throws InterruptedException {
        if (!added.isInitialized()) {
            HCIStatusCode rc = added.initialize(BTMode.DUAL, true);
            logger.debug("Direct-BT adapter initialize: {} (powered={} initialized={})", rc, added.isPowered(),
                    added.isInitialized());
            if (rc != HCIStatusCode.SUCCESS) {
                return "Adapter initialization failed: " + rc;
            }
        }
        if (!added.isPowered()) {
            if (!added.setPowered(true)) {
                HCIStatusCode rc = added.reset();
                logger.debug("Direct-BT adapter reset: {} (powered={} initialized={})", rc, added.isPowered(),
                        added.isInitialized());
                if (rc == HCIStatusCode.SUCCESS && !added.isPowered()) {
                    added.setPowered(true);
                }
                if (rc != HCIStatusCode.SUCCESS) {
                    return "Adapter power-up failed: " + rc;
                }
            }
        }
        for (int i = 0; i < waitTries && !added.isPowered(); i++) {
            Thread.sleep(waitMs);
        }
        if (!added.isPowered()) {
            return "Adapter did not power on";
        }
        return null;
    }

    private synchronized void onAdapterRemoved(BTAdapter removed) {
        BluetoothAddress wanted = adapterAddress;
        if (adapter == null || wanted == null) {
            return;
        }
        String removedMac = removed.getAddressAndType().address.toString();
        if (wanted.toString().equalsIgnoreCase(removedMac)) {
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

    @Nullable
    DirectBTBluetoothDevice handleDeviceFound(BTDevice btDevice) {
        if (disposed) {
            return null;
        }
        return handleDeviceFound(getDevice(toAddress(btDevice)), btDevice);
    }

    @Nullable
    private DirectBTBluetoothDevice handleDeviceFound(DirectBTBluetoothDevice device, BTDevice btDevice) {
        if (disposed) {
            return null;
        }
        device.updateBTDevice(btDevice);
        executor.execute(() -> deviceDiscovered(device));
        return device;
    }

    Map<String, String> getDeviceActorDiagnosticSummaries() {
        Map<String, String> result = new LinkedHashMap<>();
        forEachDevice(d -> result.put(d.getAddress().toString(), d.getActorDiagnostics().summary()));
        return Collections.unmodifiableMap(result);
    }

    boolean isDeviceEnabled(BluetoothAddress address) {
        Thing childThing = findChildThing(address);
        return childThing == null || childThing.isEnabled();
    }

    /**
     * The per-device connection security mode, read from the device Thing's {@code connectionSecurity} config
     * (mirrors {@link #isDeviceEnabled}). One of {@code none} / {@code encrypted} / {@code pin}
     * (see {@link BluetoothBindingConstants}). This is per-device rather than per-bridge so an
     * encryption request is only ever made where it is wanted. Falls back to {@code none} for an
     * unknown/handleless device.
     */
    String getDeviceConnectionSecurity(BluetoothAddress address) {
        Thing childThing = findChildThing(address);
        if (childThing != null) {
            Object security = childThing.getConfiguration()
                    .get(BluetoothBindingConstants.CONFIGURATION_CONNECTION_SECURITY);
            if (security instanceof String s && !s.isBlank()) {
                return s.trim();
            }
        }
        return BluetoothBindingConstants.CONNECTION_SECURITY_NONE;
    }

    /**
     * The per-device LE PHY preference, read from the device Thing's {@code phy} config: one of
     * {@code auto} / {@code 1m} / {@code 2m} / {@code coded}. Applied best-effort after each connect;
     * {@code auto} means no PHY request is made (controller default).
     */
    String getDevicePhy(BluetoothAddress address) {
        Thing childThing = findChildThing(address);
        if (childThing != null) {
            Object phy = childThing.getConfiguration().get("phy");
            if (phy instanceof String s && !s.isBlank()) {
                return s.trim().toLowerCase(Locale.ROOT);
            }
        }
        return "auto";
    }

    /**
     * The per-device GATT seed-cache policy ("Fast Reconnect" in the UI), read from the device Thing's
     * {@code gattCache} config: {@code auto} (validated reuse, default), {@code trust} (unvalidated reuse)
     * or {@code off} (full rediscovery every connection). Unknown values fall back to {@code auto}.
     */
    GattCacheMode getDeviceGattCacheMode(BluetoothAddress address) {
        Thing childThing = findChildThing(address);
        if (childThing != null) {
            Object mode = childThing.getConfiguration().get("gattCache");
            if (mode instanceof String s) {
                switch (s.trim().toLowerCase(Locale.ROOT)) {
                    case "off":
                        return GattCacheMode.OFF;
                    case "trust":
                        return GattCacheMode.TRUST;
                    default:
                        break;
                }
            }
        }
        return GattCacheMode.AUTO;
    }

    /**
     * The per-device static passkey/PIN for authenticated (PASSKEY_ENTRY) pairing, read from the device Thing's
     * {@code passkey} config, or {@code -1} if none is configured. A valid BLE passkey is 0..999999.
     */
    int getDevicePasskey(BluetoothAddress address) {
        Thing childThing = findChildThing(address);
        if (childThing != null) {
            Object passkey = childThing.getConfiguration().get(BluetoothBindingConstants.CONFIGURATION_PASSKEY);
            if (passkey instanceof Number n) {
                return n.intValue();
            }
            if (passkey instanceof String s && !s.isBlank()) {
                try {
                    return Integer.parseInt(s.trim());
                } catch (NumberFormatException e) {
                    logger.warn("Invalid passkey '{}' configured for {}; ignoring", s, address);
                }
            }
        }
        return -1;
    }

    /** @return the child device Thing whose configured address matches, or {@code null} if none is registered. */
    private @Nullable Thing findChildThing(BluetoothAddress address) {
        String addrStr = address.toString();
        for (Thing childThing : getThing().getThings()) {
            Object childAddr = childThing.getConfiguration().get(BluetoothBindingConstants.CONFIGURATION_ADDRESS);
            if (addrStr.equalsIgnoreCase(String.valueOf(childAddr))) {
                return childThing;
            }
        }
        return null;
    }

    private static BluetoothAddress toAddress(BTDevice btDevice) {
        return new BluetoothAddress(btDevice.getAddressAndType().address.toString().toUpperCase(Locale.ROOT));
    }

    /** Pool for the device's blocking GATT operations. */
    ExecutorService getExecutor() {
        return executor;
    }

    /** Pool for openHAB notification fanout (isolated from the blocking GATT operations). */
    ExecutorService getNotifyExecutor() {
        return notifyExecutor;
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
            // The worker takes reconcileLock so an in-flight tick (already past its disposed check) cannot race
            // the teardown; the reconcile job itself was cancelled above. Lock order is this -> reconcileLock
            // (same as onAdapterAdded -> startReconciler), and the tick never takes the handler monitor, so no
            // inversion. If the lock holder is wedged in a native call, the join timeout still bounds dispose().
            runTimeBoxed("device close + adapter detach", DISPOSE_NATIVE_TIMEOUT_MS, () -> {
                synchronized (reconcileLock) {
                    forEachDevice(DirectBTBluetoothDevice::close);
                    detachAdapter();
                }
            });
            managerReady = false;
        }
        super.dispose();
    }

    private void cancelInitJob() {
        ScheduledFuture<?> job = initJob;
        if (job != null) {
            // cancel(false): the job body is non-blocking (getManager() is a getNow), and this is also called
            // from the job's own tick, where self-interrupting the pool thread would be a dirty signal.
            job.cancel(false);
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
