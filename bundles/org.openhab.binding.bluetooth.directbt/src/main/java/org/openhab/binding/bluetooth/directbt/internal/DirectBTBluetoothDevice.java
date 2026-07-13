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

import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

import org.direct_bt.BTDevice;
import org.direct_bt.BTGattChar;
import org.direct_bt.BTGattCharListener;
import org.direct_bt.BTGattService;
import org.direct_bt.BTSecurityLevel;
import org.direct_bt.EInfoReport;
import org.direct_bt.GattCharPropertySet;
import org.direct_bt.HCIStatusCode;
import org.direct_bt.LE_PHYs;
import org.direct_bt.PairingMode;
import org.direct_bt.SMPIOCapability;
import org.direct_bt.SMPPairingState;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.bluetooth.BaseBluetoothDevice;
import org.openhab.binding.bluetooth.BluetoothAddress;
import org.openhab.binding.bluetooth.BluetoothBindingConstants;
import org.openhab.binding.bluetooth.BluetoothCharacteristic;
import org.openhab.binding.bluetooth.BluetoothDescriptor;
import org.openhab.binding.bluetooth.BluetoothService;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.AdapterReconciler;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.DevicePort;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.DeviceReconciler;
import org.openhab.binding.bluetooth.notification.BluetoothConnectionStatusNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link org.openhab.binding.bluetooth.BluetoothDevice} backed by a Direct-BT {@link BTDevice}.
 * <p>
 * Carries the Direct-BT device handle, copies advertisement-derived fields into the openHAB model, and
 * maps connect / GATT service discovery / characteristic read-write-notify onto the Direct-BT API.
 * Connection-state transitions are driven by this device's {@link DeviceReconciler} (run by the bridge's
 * single reconcile tick), which polls the native {@code BTDevice.getConnected()} truth rather than trusting
 * Direct-BT's connection events; the blocking Direct-BT read/write calls are run on the bridge's notification
 * executor and surfaced as {@link CompletableFuture}s.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
public class DirectBTBluetoothDevice extends BaseBluetoothDevice implements DevicePort {

    // Scan-assisted LE connection parameters, tuned for reliability on marginal/weak-RSSI links (units of
    // 0.625ms for scan, 1.25ms for conn interval, 10ms for supervision). PINNED to match the dead-stable BlueZ
    // profile on the same hardware: BlueZ negotiates a TIGHT 30ms interval (min==max) + 2000ms supervision and
    // never drops a read. With a loose 30-50ms range the controller picked 50ms, and on the 50ms link the
    // peripheral periodically LL-ACKed a Read Request but never returned the Read Response (wire-validated:
    // peripheral-side stall, not host/controller), which Direct-BT then correctly timed out -> disconnect.
    // Pinning the interval to exactly 30ms (min==max==24) removes the wide-range window that triggers it.
    // TODO: make these overridable via bridge-level config (the generic device thing-type is core-owned, so
    // per-device config isn't available).
    private static final short LE_SCAN_INTERVAL = (short) 24; // 15ms
    private static final short LE_SCAN_WINDOW = (short) 24; // 15ms
    private static final short CONN_INTERVAL_MIN = (short) 24; // 30ms (pinned == max, matches BlueZ)
    private static final short CONN_INTERVAL_MAX = (short) 24; // 30ms (pinned == min, matches BlueZ)
    private static final short CONN_LATENCY = (short) 0;
    private static final short CONN_SUPERVISION_TIMEOUT = (short) 200; // 2000ms (units of 10ms, matches BlueZ)

    private final Logger logger = LoggerFactory.getLogger(DirectBTBluetoothDevice.class);

    private final DirectBTBridgeHandler bridge;
    private final ExecutorService executor;
    // This device's reconciler (owns connect/state-flag/gatt). Driven by the bridge's single reconcile tick.
    private final DeviceReconciler reconciler;

    private volatile @Nullable BTDevice device;
    // The openHAB core's connection intent, captured from connect()/disconnect(). The core uses disconnect() for
    // two different cases: a real idle disconnect after services are resolved, and a generic "connected but GATT
    // unresolved" retry. Only the former should clear this flag.
    private volatile boolean wantConnected;
    // When the reconciler last marked this device connected. Guards reconnect() (the generic handler's
    // GATT-unresolved bounce) against firing while the post-connect GATT resolve is still in flight.
    private volatile long connectedAtMillis;
    /**
     * How long after a connect the handler's GATT-recovery bounce is refused (resolve still in flight). Must
     * comfortably cover a full fresh pairing (~8s) PLUS the next resolve retry after it, otherwise the bounce
     * fires in the gap between "pairing done" and "resolve retried" and tears down a link that was about to
     * resolve (observed live as a periodic bounce/re-pair loop).
     */
    static final long RECONNECT_GRACE_MILLIS = 25_000;
    // Until when the bridge's orphan-adoption sweep must leave this device alone. Set on markDisconnected():
    // a DELIBERATE teardown issues a native disconnect that completes asynchronously, so for a short window the
    // native device still reads connected — adopting it back would resurrect the link we just chose to drop
    // (observed live: every GATT-recovery bounce was immediately undone by the sweep). A fresh advertisement
    // (deviceFound) re-attaches the device the intended way.
    private volatile long suppressAdoptionUntilMillis;
    /** Adoption quiet window after a deliberate teardown (covers the async native disconnect completing). */
    static final long ADOPTION_QUIET_MILLIS = 10_000;
    // Whether the persisted bond (if any) was already uploaded onto this device object. One attempt per
    // handle: mid-session the kernel holds the keys anyway; the upload matters after a restart.
    private volatile boolean bondApplied;
    // Clock behind the grace/quiet windows (injectable for tests; the reconciler shares it).
    private final Clock clock;

    // Maps an openHAB characteristic UUID to the Direct-BT characteristic handle (populated on discovery).
    private final Map<UUID, BTGattChar> gattCharByUuid = new ConcurrentHashMap<>();
    // Active notification listeners per characteristic UUID, so we can unregister on disable/dispose.
    private final Map<UUID, BTGattCharListener> notifyListeners = new ConcurrentHashMap<>();

    public DirectBTBluetoothDevice(DirectBTBridgeHandler adapter, BluetoothAddress address) {
        this(adapter, address, Clock.systemUTC());
    }

    /** Test constructor: inject the clock driving the reconnect-grace window and the device reconciler. */
    DirectBTBluetoothDevice(DirectBTBridgeHandler adapter, BluetoothAddress address, Clock clock) {
        super(adapter, address);
        this.bridge = adapter;
        this.executor = adapter.getExecutor();
        this.clock = clock;
        this.reconciler = new DeviceReconciler(logger, this, () -> {
            AdapterReconciler ar = bridge.getAdapterReconciler();
            return ar != null && ar.isScanOff();
        }, adapter.getResetBudget(), bridge::requestAdapterReset, clock);
    }

    /** @return this device's reconciler (driven by the bridge's reconcile tick). */
    DeviceReconciler getReconciler() {
        return reconciler;
    }

    /**
     * Update the backing Direct-BT device handle and copy advertisement fields into the openHAB model.
     * Always refreshes the last-seen time. Must be called for every advert so that {@code deviceReachable()}
     * sees a valid RSSI (discovery is filtered out otherwise) and inactive-device cleanup works.
     */
    synchronized void updateBTDevice(BTDevice btDevice) {
        this.device = btDevice;
        updateLastSeenTime();

        short rssiValue = btDevice.getRSSI();
        if (rssiValue != 0) {
            setRssi(rssiValue);
        }
        String deviceName = btDevice.getName();
        if (deviceName != null && !deviceName.isEmpty()) {
            setName(deviceName);
        }
        short txPowerValue = btDevice.getTxPower();
        if (txPowerValue != 0 && txPowerValue != 127) { // 127 = "not available" per the BT spec
            setTxPower(txPowerValue);
        }
        EInfoReport eir = btDevice.getEIR();
        if (eir != null) {
            Map<Short, byte[]> manData = eir.getManufacturerData();
            if (manData != null) {
                manData.keySet().stream().filter(Objects::nonNull).findFirst()
                        .ifPresent(id -> setManufacturerId(id & 0xFFFF));
            }
            // Derive advertising connectability from the AD PDU type so discovery can surface connectable
            // devices without a connect probe and leave non-connectable beacons alone. Only definitive types
            // update the flag; a bare SCAN_RSP / UNDEFINED frame carries no connectability info and must not
            // downgrade a connectable device already observed via its ADV_IND frame.
            EInfoReport.AD_PDU_Type evtType = eir.getEvtType();
            if (evtType.isConnectable()) {
                setConnectable(true);
            } else if (evtType.isNonConnectable()) {
                setConnectable(false);
            }
        }
    }

    @Nullable
    BTDevice getBTDevice() {
        return device;
    }

    // --- openHAB connection API: INTENT setters only (the reconciler does the actual work) ----------
    // The core's ConnectedBluetoothHandler calls connect()/disconnect() and reconciles against
    // getConnectionState(). In the eventually-consistent model we do NOT issue connectLE here (that races a
    // stuck controller into COMMAND_DISALLOWED). We only record intent and requeue a reconcile tick; the
    // bridge's single-threaded reconciler issues the real connectLE when it observes the scan is off.

    @Override
    public boolean connect() {
        // Admission control: only wanted (listener-having) devices ever connect. The core connect-probes every
        // discovered device without a listener to fingerprint it; refusing those keeps the controller clean.
        if (getListeners().isEmpty()) {
            logger.debug("connect() refused for {}: no listeners (instance {})", address,
                    System.identityHashCode(this));
            return false;
        }
        logger.debug("connect() accepted for {}: wantConnected=true (instance {})", address,
                System.identityHashCode(this));
        wantConnected = true;
        bridge.requeueReconcile();
        // Return true (intent accepted) so the core's reconnect loop treats this as "connecting in progress" and
        // stops hammering; the reconciler drives the real state and getConnectionState() reflects native truth.
        return true;
    }

    @Override
    public boolean disconnect() {
        // disconnect() is a real intent change: the core no longer wants this device connected (user disabled it,
        // or alwaysConnected=false idle-disconnect). Clear the intent so the reconciler tears the ACL down.
        wantConnected = false;
        bridge.requeueReconcile();
        return true;
    }

    @Override
    public boolean reconnect() {
        // reconnect() = "bounce the link, KEEP the connection intent" (the generic handler's GATT-unresolved
        // recovery). Do NOT clear wantConnected. Drop the live ACL so the next reconcile re-discovers and
        // re-connects from a fresh advert (markDisconnected nulls the native handle + resets the GATT model).
        //
        // GRACE WINDOW: the generic handler polls every pollingInterval (can be 2s) and requests this bounce the
        // moment it sees CONNECTED with services unresolved. But the reconciler resolves GATT asynchronously right
        // after markConnected(), and that resolve can take longer than one poll period (measured ~2.1s on an
        // encrypted link). Without the grace the handler bounces every freshly-established connection before its
        // resolve finishes -> an endless connect/bounce loop. A bounce within the grace of the last
        // markConnected() is refused (the in-flight resolve IS the recovery); after it, honoured (resolve hung).
        if (isNativeConnected() && clock.millis() - connectedAtMillis < RECONNECT_GRACE_MILLIS) {
            logger.debug("Reconnect requested for {} within grace of connect; GATT resolve in flight, ignoring",
                    address);
            bridge.requeueReconcile(); // reconciler re-runs resolveGatt if still unresolved
            return true;
        }
        if (isNativeConnected() && isPairing()) {
            // Never bounce mid-SMP: an authenticated (passkey) negotiation can stall and retry internally for
            // tens of seconds, and GATT stays unresolved the whole time. Tearing the link down here pre-empts
            // SMP's own recovery and turns a slow pairing into an endless connect/bounce loop. Same freeze
            // discipline as the reconciler's pairing-aware connect deadline.
            logger.debug("Reconnect requested for {} while SMP pairing is negotiating; ignoring", address);
            bridge.requeueReconcile();
            return true;
        }
        logger.debug("Reconnect requested for {} (GATT recovery); keeping connection intent", address);
        if (isNativeConnected() || isFlagConnected() || isFlagConnecting()) {
            markDisconnected();
        }
        bridge.requeueReconcile();
        return true;
    }

    /** Update the inherited connection-state field AND notify listeners (BaseBluetoothDevice does neither). */
    private void setConnectionState(ConnectionState state) {
        if (this.connectionState != state) {
            this.connectionState = state;
            notifyListeners(BluetoothEventType.CONNECTION_STATE, new BluetoothConnectionStatusNotification(state));
        }
    }

    /** Best-effort unregister of all active notification listeners (native peers) before the map is cleared. */
    private void releaseNotifyListeners() {
        for (Map.Entry<UUID, BTGattCharListener> entry : notifyListeners.entrySet()) {
            BTGattChar gattChar = gattCharByUuid.get(entry.getKey());
            try {
                if (gattChar != null) {
                    gattChar.configNotificationIndication(false, false, new boolean[2]);
                    gattChar.removeCharListener(entry.getValue());
                }
            } catch (RuntimeException e) {
                logger.debug("Error removing char listener for {}", address, e);
            }
        }
        notifyListeners.clear();
    }

    /**
     * Reset the inherited GATT model: clear the cached service list, mark service discovery incomplete (so the
     * next connect re-fires SERVICES_DISCOVERED), and drop the native characteristic-handle map.
     */
    private void resetGattModel() {
        clearServices();
        gattCharByUuid.clear();
    }

    // --- DevicePort: the operations the DeviceReconciler drives (all polled native truth / idempotent) ----

    @Override
    public boolean isWanted() {
        return bridge.isDeviceEnabled(address) && wantConnected;
    }

    @Override
    public boolean hasNativeDevice() {
        return device != null;
    }

    @Override
    public boolean isNativeConnected() {
        BTDevice dev = device;
        try {
            // Direct-BT's Java-side connected flag can go stale after a silent link drop. We deliberately
            // do NOT probe here: a pingGATT arbitration was tried and rejected, since it also fails on a
            // healthy just-connected link whose services are not discovered yet, tearing down every fresh
            // connection. Staleness is instead detected downstream by the reconciler's resolve-fail streak,
            // whose teardown (unconditional disconnect + native remove) recovers the device.
            return dev != null && dev.getConnected();
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public boolean isGattResolved() {
        return !gattCharByUuid.isEmpty();
    }

    @Override
    public boolean isFlagConnected() {
        return connectionState == ConnectionState.CONNECTED;
    }

    @Override
    public boolean isFlagConnecting() {
        return connectionState == ConnectionState.CONNECTING;
    }

    @Override
    public boolean isPairing() {
        BTDevice dev = device;
        if (dev == null) {
            return false;
        }
        try {
            return isNegotiating(dev.getPairingState());
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * @return true iff {@code state} is one of the SMP "actively negotiating" values — the band during which
     *         {@code setConnSecurityAuto} is still doing its connect/disconnect cycles to negotiate keys. The three
     *         terminal states ({@code NONE}, {@code FAILED}, {@code COMPLETED}) return false so the reconciler's
     *         connect deadline resumes: on COMPLETED the link is up and the state-flag sync takes over; on
     *         FAILED/NONE the deadline expires normally and the device is retried (unbonded devices simply never
     *         enter this band, so their behaviour is unchanged). This is intentionally a pure classification of the
     *         transport enum so the reconciler stays transport-agnostic.
     */
    private static boolean isNegotiating(SMPPairingState state) {
        switch (state) {
            case REQUESTED_BY_RESPONDER:
            case FEATURE_EXCHANGE_STARTED:
            case FEATURE_EXCHANGE_COMPLETED:
            case PASSKEY_EXPECTED:
            case NUMERIC_COMPARE_EXPECTED:
            case PASSKEY_NOTIFY:
            case OOB_EXPECTED:
            case KEY_DISTRIBUTION:
                return true;
            default:
                return false;
        }
    }

    @Override
    public void markConnected() {
        connectedAtMillis = clock.millis();
        setConnectionState(ConnectionState.CONNECTED);
        applyPhyPreference();
    }

    /**
     * Requests the configured LE PHY for the freshly established connection (device Thing config {@code phy}).
     * Every connection starts on LE 1M; a "coded" preference buys roughly 12 dB of link budget for weak/far
     * devices, "2m" doubles bandwidth. Best-effort: the controllers negotiate, and an unsupported PHY on
     * either side simply leaves the connection on 1M.
     */
    private void applyPhyPreference() {
        String phy = bridge.getDevicePhy(address);
        if (phy == null || "auto".equals(phy)) {
            return;
        }
        BTDevice dev = device;
        if (dev == null) {
            return;
        }
        LE_PHYs.PHY requested = switch (phy) {
            case "2m" -> LE_PHYs.PHY.LE_2M;
            case "coded" -> LE_PHYs.PHY.LE_CODED;
            default -> LE_PHYs.PHY.LE_1M;
        };
        try {
            LE_PHYs phys = new LE_PHYs(requested.value);
            HCIStatusCode rc = dev.setConnectedLE_PHY(phys, phys);
            logger.debug("LE PHY request {} for {} -> {}", phy, address, rc);
        } catch (RuntimeException e) {
            logger.debug("LE PHY request {} for {} threw", phy, address, e);
        }
    }

    @Override
    public void markConnecting() {
        setConnectionState(ConnectionState.CONNECTING);
    }

    @Override
    public void markDisconnected() {
        releaseNotifyListeners();
        resetGattModel();
        // ZOMBIE-ACL GUARD: if the native link is still up, tear it down BEFORE dropping the handle. Nulling the
        // handle of a live connection leaks the ACL: nothing else holds a reference to disconnect it, the
        // peripheral stays connected (so it never advertises), and rediscovery/reconnect become impossible until
        // the link is killed externally (observed live: Thing bounce with an up link left a permanent zombie ACL).
        BTDevice dev = device;
        if (dev != null) {
            try {
                // UNCONDITIONAL best-effort disconnect: getConnected() is not a reliable gate. An object adopted
                // from an orphaned controller ACL can report false while the controller still holds the link —
                // skipping the disconnect then leaks the ACL permanently: the peripheral never advertises again,
                // adoption re-mints a bare shell for it each tick, GATT never resolves on the shell, and the
                // device stays unreachable until the HCI channel is closed. Disconnecting an
                // actually-unconnected device is a cheap native no-op error.
                logger.debug("markDisconnected for {}: unconditional best-effort native disconnect (getConnected={})",
                        address, dev.getConnected());
                dev.disconnect();
            } catch (RuntimeException e) {
                logger.debug("Best-effort native disconnect for {} failed", address, e);
            }
            // DISCARD the native object, don't just unlink it. Direct-BT keeps BTDevice instances in a shared
            // list, and after a silent link drop an instance can carry permanently stale connection state
            // (both getConnected() and getConnectionHandle() keep reporting the dead link). If we only null
            // our reference, the very next advertisement re-adopts the SAME poisoned object via updateBTDevice
            // and the stale "connected" verdict returns: resolve-fail -> markDisconnected -> re-adopt, forever.
            // remove() evicts it from the shared list so the next advertisement mints a FRESH native object.
            try {
                dev.remove();
            } catch (RuntimeException e) {
                logger.debug("Best-effort native remove for {} failed", address, e);
            }
        }
        // Drop the stale native handle: after a disconnect the BTDevice no longer represents a usable link, and a
        // reconnect attempt on the old handle is exactly the path that wedges (CSR COMMAND_DISALLOWED / a connectLE
        // that the controller silently never completes). Clearing it makes the device hasNativeDevice()==false, so
        // the discovery reconciler turns the scan back on and re-finds the device from a FRESH advertisement before
        // the next connect -- the same "trust the fresh frame, not a cached object" discipline the rest of the
        // stack relies on. updateBTDevice() installs the new handle when the scan rediscovers it.
        suppressAdoptionUntilMillis = clock.millis() + ADOPTION_QUIET_MILLIS;
        device = null;
        setConnectionState(ConnectionState.DISCONNECTED);
    }

    /**
     * @return true iff the bridge's orphan-adoption sweep may re-attach a still-connected native device to this
     *         wrapper. False during the quiet window after a deliberate teardown (see markDisconnected()).
     */
    boolean adoptionAllowed() {
        return clock.millis() >= suppressAdoptionUntilMillis;
    }

    @Override
    public HCIStatusCode connectNative() {
        BTDevice dev = device;
        if (dev == null) {
            return HCIStatusCode.INTERNAL_FAILURE;
        }
        // Reset the GATT model + the servicesDiscovered latch at the START of every connect, mirroring the BlueZ
        // transport (BlueZBluetoothDevice.connect() clears services up front; see openhab-addons #20985). This
        // guarantees that after the connection is established the post-connect discovery re-fires
        // SERVICES_DISCOVERED, so the generic device handler's onServicesDiscovered() runs and re-populates its
        // channel/poll map. Without it, a handler re-bound to an already-resolved device (a hot binding redeploy,
        // or any reconnect where the latch stayed true) sits on an empty channel map and silently never polls.
        resetGattModel();
        try {
            // The reconciler has already ensured the adapter scan is OFF (the controller rejects create-connection
            // while scanning) and owns the connect lifecycle, so no inline pre-connect cleanup is needed. A former
            // dev.disconnect() residue-clear here was ablated (2026-06-26, from-scratch CSR rebuild) and proven
            // redundant: connect succeeds first-try and holds dead-stable (45/45 reads, 0 disconnects) without it,
            // and the reconciler recovers from the CSR COMMAND_DISALLOWED/INTERNAL_TIMEOUT quirk on its own.
            //
            // Connection security is chosen PER DEVICE from the device Thing's connectionSecurity config, so an
            // encryption request is only ever made where it is wanted. We always use the EXPLICIT
            // setConnSecurity(level, iocap), NOT setConnSecurityAuto: the adapter runs in Master (central) role and
            // setConnSecurityAuto is a NO-OP for a master (BTDevice::setConnSecurityAuto returns false when
            // BTRole::Master -- verified in direct_bt source + live: it left sec[... auto UNSET, pairing NONE ...],
            // i.e. no security requested from our side). setConnSecurity with an explicit level DOES take in master
            // role and drives the request from the central side. Level per mode:
            // - "encrypted": Just Works -> ENC_ONLY + NO_INPUT_NO_OUTPUT (encrypted, unauthenticated). STRICT:
            // never downgrades to an unencrypted connection.
            // - "pin": Passkey Entry -> ENC_AUTH + KEYBOARD_ONLY (encrypted AND MITM-authenticated). The device asks
            // for the key (SMP PASSKEY_EXPECTED) and the bridge supplies the configured passkey; KEYBOARD_ONLY tells
            // the peer we input the key it holds. Enforced fail-closed via securityRequirementUnmet().
            // - "none" (default): NONE.
            String securityMode = bridge.getDeviceConnectionSecurity(address);
            boolean secured = !BluetoothBindingConstants.CONNECTION_SECURITY_NONE.equalsIgnoreCase(securityMode);
            if (BluetoothBindingConstants.CONNECTION_SECURITY_PIN.equalsIgnoreCase(securityMode)) {
                dev.setConnSecurity(BTSecurityLevel.ENC_AUTH, SMPIOCapability.KEYBOARD_ONLY);
            } else if (BluetoothBindingConstants.CONNECTION_SECURITY_ENCRYPTED.equalsIgnoreCase(securityMode)) {
                dev.setConnSecurity(BTSecurityLevel.ENC_ONLY, SMPIOCapability.NO_INPUT_NO_OUTPUT);
            } else {
                dev.setConnSecurity(BTSecurityLevel.NONE, SMPIOCapability.NO_INPUT_NO_OUTPUT);
            }
            // BOND PERSISTENCE (restart survival): before the first connect of this device object, upload any
            // persisted SMP keys so the reconnect reuses the stored bond (PRE_PAIRED) instead of re-pairing —
            // essential for peers that limit pairings or only pair in an explicit pairing mode. Once per
            // handle: mid-session the keys already live in the kernel; this matters after a restart/power
            // cycle when everything in-memory is gone. Must run BEFORE connectLE (upload is rejected on a
            // connected device).
            if (secured && !bondApplied) {
                bondApplied = true; // one attempt per device object; a fresh pairing overwrites the store anyway
                BondStore store = bridge.getBondStore();
                if (store != null && store.apply(dev)) {
                    logger.debug("Persisted bond applied to {}; reconnect will reuse the stored keys", address);
                }
            }
            return dev.connectLE(LE_SCAN_INTERVAL, LE_SCAN_WINDOW, CONN_INTERVAL_MIN, CONN_INTERVAL_MAX, CONN_LATENCY,
                    CONN_SUPERVISION_TIMEOUT);
        } catch (RuntimeException e) {
            logger.debug("Direct-BT connectNative to {} threw", address, e);
            return HCIStatusCode.INTERNAL_FAILURE;
        }
    }

    @Override
    public void disconnectNative() {
        BTDevice dev = device;
        if (dev == null) {
            return;
        }
        try {
            dev.disconnect();
        } catch (RuntimeException e) {
            logger.debug("Direct-BT disconnectNative from {} threw", address, e);
        }
    }

    @Override
    public boolean hasStalePairing() {
        BTDevice dev = device;
        if (dev == null) {
            return false;
        }
        try {
            // "Pre-paired" == the device holds stored SMP keys a reconnect will reuse (BTDevice.isPrePaired()).
            // The reconciler only calls this after a create-connection has failed to establish, so a pre-paired
            // device here means the stored key is the thing blocking the (encrypted) reconnect.
            return dev.isPrePaired();
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public void clearStalePairing() {
        // The stored key is dead (the peer no longer honours it) — the persisted copy must go too, or the
        // dead bond is resurrected from disk on every restart and the self-heal never sticks.
        BondStore store = bridge.getBondStore();
        if (store != null) {
            store.delete(address);
        }
        BTDevice dev = device;
        if (dev == null) {
            return;
        }
        try {
            // unpair() clears the in-memory SMP state (clearSMPStates -> is_pre_paired=false) so the next connect
            // negotiates a fresh pairing rather than reusing a stored key the peer no longer honours.
            dev.unpair();
        } catch (RuntimeException e) {
            logger.debug("Direct-BT clearStalePairing for {} threw", address, e);
        }
    }

    @Override
    public boolean securityRequirementUnmet() {
        // Only the authenticated "pin" mode has a requirement stricter than "connected" to enforce. It demands an
        // MITM-authenticated link (ENC_AUTH); if SMP negotiated down to Just-Works/unencrypted (peer can't do
        // MITM), the achieved level is below ENC_AUTH and we must refuse rather than expose GATT unauthenticated.
        if (!BluetoothBindingConstants.CONNECTION_SECURITY_PIN
                .equalsIgnoreCase(bridge.getDeviceConnectionSecurity(address))) {
            return false;
        }
        BTDevice dev = device;
        if (dev == null) {
            return false;
        }
        try {
            // getConnSecurityLevel() alone is NOT sufficient: Direct-BT seeds sec_level_conn with the REQUESTED
            // level at connect setup, so a Just-Works fallback can still read back ENC_AUTH. The reliable signal is
            // the achieved PairingMode -- the actual method used. Anything in the unauthenticated set
            // (NONE / NEGOTIATING / JUST_WORKS) means MITM was NOT achieved, so an authenticated ("pin") mode is
            // unmet. The authenticated modes (PASSKEY_ENTRY_*, NUMERIC_COMPARE_*, OUT_OF_BAND) and PRE_PAIRED
            // (reusing keys from an earlier authenticated pairing) satisfy it.
            return isUnauthenticated(dev.getPairingMode());
        } catch (RuntimeException e) {
            // Can't confirm the achieved pairing mode -> treat as unmet (fail closed) for an authenticated mode.
            logger.debug("Direct-BT security check for {} threw; treating pin requirement as unmet", address, e);
            return true;
        }
    }

    /** @return true iff {@code mode} provides NO MITM authentication (the pin requirement would be unmet). */
    private static boolean isUnauthenticated(PairingMode mode) {
        switch (mode) {
            case NONE:
            case NEGOTIATING:
            case JUST_WORKS:
                return true;
            default: // PASSKEY_ENTRY_*, NUMERIC_COMPARE_*, OUT_OF_BAND, PRE_PAIRED are authenticated / key-reuse
                return false;
        }
    }

    @Override
    public void resolveGatt() {
        discoverServices();
    }

    @Override
    public String id() {
        return address.toString();
    }

    @Override
    public boolean discoverServices() {
        BTDevice dev = device;
        if (dev == null) {
            return false;
        }
        try {
            // Always refresh the native handle map: on a reconnect the BTGattChar handles are new, so we must
            // re-map every characteristic even for services already present in the openHAB model (otherwise
            // gattCharByUuid stays empty after a reconnect and read/write/notify fail with "not found").
            gattCharByUuid.clear();
            for (BTGattService gattService : dev.getGattServices()) {
                UUID serviceUuid = UUID.fromString(gattService.getUUID());
                BluetoothService existing = getServices(serviceUuid);
                if (existing == null) {
                    BluetoothService service = new BluetoothService(serviceUuid, true);
                    for (BTGattChar gattChar : gattService.getChars()) {
                        UUID charUuid = UUID.fromString(gattChar.getUUID());
                        BluetoothCharacteristic characteristic = new BluetoothCharacteristic(charUuid, 0);
                        characteristic.setProperties(mapProperties(gattChar.getProperties()));
                        service.addCharacteristic(characteristic);
                        gattCharByUuid.put(charUuid, gattChar);
                    }
                    addService(service);
                } else {
                    // Service already in the model (reconnect): just refresh the native handles.
                    for (BTGattChar gattChar : gattService.getChars()) {
                        gattCharByUuid.put(UUID.fromString(gattChar.getUUID()), gattChar);
                    }
                }
            }
        } catch (RuntimeException e) {
            logger.debug("Direct-BT service discovery for {} failed", address, e);
            return false;
        }
        if (!getServices().isEmpty()) {
            notifyListeners(BluetoothEventType.SERVICES_DISCOVERED);
        }
        // Do NOT resume scanning while this device is connected: an active LE scan concurrent with a live ACL is
        // a classic single-radio ACL-killer. The DiscoveryReconciler computes scanWanted from device state, so it
        // keeps the scan off as long as this device stays connected, and turns it back on (to rediscover) only
        // when the device reconciler observes the native link is gone.
        return true;
    }

    /** @return the cached native characteristic, or {@code null} if not connected/known. */
    private @Nullable BTGattChar connectedChar(UUID charUuid) {
        BTDevice dev = device;
        if (dev == null || !dev.getConnected()) {
            // The native link is gone while a read/write is attempted. Requeue a reconcile so the device
            // reconciler observes native!=flag and drives the disconnect transition (the reconciler is the single
            // owner of the state flag, so we do not mutate it from here).
            if (connectionState == ConnectionState.CONNECTED) {
                bridge.requeueReconcile();
            }
            return null;
        }
        return gattCharByUuid.get(charUuid);
    }

    @Override
    public CompletableFuture<byte[]> readCharacteristic(BluetoothCharacteristic characteristic) {
        UUID charUuid = characteristic.getUuid();
        return CompletableFuture.supplyAsync(() -> {
            try {
                BTGattChar gattChar = connectedChar(charUuid);
                if (gattChar == null) {
                    throw new IllegalStateException("Characteristic not available (disconnected?): " + charUuid);
                }
                byte[] value = gattChar.readValue();
                if (value.length == 0) {
                    logger.debug("Direct-BT read of {} on {} returned empty value; suppressing update", charUuid,
                            address);
                    return value;
                }
                notifyListeners(BluetoothEventType.CHARACTERISTIC_UPDATED, characteristic, value);
                return value;
            } catch (RuntimeException e) {
                throw new CompletionException(e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<@Nullable Void> writeCharacteristic(BluetoothCharacteristic characteristic, byte[] value) {
        UUID charUuid = characteristic.getUuid();
        return CompletableFuture.runAsync(() -> {
            try {
                BTGattChar gattChar = connectedChar(charUuid);
                if (gattChar == null) {
                    throw new IllegalStateException("Characteristic not available (disconnected?): " + charUuid);
                }
                // withResponse=true (acknowledged write) unless only write-without-response is supported.
                boolean withResponse = gattChar.getProperties().isSet(GattCharPropertySet.Type.WriteWithAck);
                if (!gattChar.writeValue(value, withResponse)) {
                    throw new IllegalStateException("writeValue returned false");
                }
            } catch (RuntimeException e) {
                throw new CompletionException(e);
            }
        }, executor);
    }

    @Override
    public boolean isNotifying(BluetoothCharacteristic characteristic) {
        return notifyListeners.containsKey(characteristic.getUuid());
    }

    @Override
    public CompletableFuture<@Nullable Void> enableNotifications(BluetoothCharacteristic characteristic) {
        UUID charUuid = characteristic.getUuid();
        // Reserve the slot atomically before native registration so two concurrent callers can't both
        // register a native listener (the second put would orphan the first, making it unremovable).
        BTGattCharListener listener = new DirectBTGattCharListener(charUuid);
        if (notifyListeners.putIfAbsent(charUuid, listener) != null) {
            return CompletableFuture.completedFuture(null); // already enabled / being enabled
        }
        return CompletableFuture.runAsync(() -> {
            try {
                BTGattChar gattChar = connectedChar(charUuid);
                if (gattChar == null) {
                    throw new IllegalStateException("Characteristic not available (disconnected?): " + charUuid);
                }
                if (!gattChar.addCharListener(listener)) {
                    throw new IllegalStateException("addCharListener returned false");
                }
                if (!gattChar.enableNotificationOrIndication(new boolean[2])) {
                    gattChar.removeCharListener(listener);
                    throw new IllegalStateException("enableNotificationOrIndication returned false");
                }
            } catch (RuntimeException e) {
                notifyListeners.remove(charUuid, listener); // roll back the reservation on failure
                throw new CompletionException(e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<@Nullable Void> disableNotifications(BluetoothCharacteristic characteristic) {
        UUID charUuid = characteristic.getUuid();
        BTGattChar gattChar = gattCharByUuid.get(charUuid);
        BTGattCharListener listener = notifyListeners.remove(charUuid);
        if (gattChar == null || listener == null) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(() -> {
            try {
                gattChar.configNotificationIndication(false, false, new boolean[2]);
                gattChar.removeCharListener(listener);
            } catch (RuntimeException e) {
                throw new CompletionException(e);
            }
        }, executor);
    }

    @Override
    public boolean enableNotifications(BluetoothDescriptor descriptor) {
        // Descriptor-level notifications are not used by the Direct-BT transport (notifications are driven
        // at the characteristic level via the CCCD handled by enableNotificationOrIndication).
        return false;
    }

    @Override
    public boolean disableNotifications(BluetoothDescriptor descriptor) {
        return false;
    }

    /**
     * Best-effort teardown on bridge/handler disposal (OH shutdown or a hot binding redeploy): release native
     * notification listeners, drop the native ACL, and reset the openHAB-model connection + service-discovery
     * state. Resetting the model state (via {@link #markDisconnected()}, which calls {@code clearServices()} and
     * nulls the handle) is the important part: if we left the link up and {@code servicesDiscovered} latched true,
     * a freshly re-bound generic device handler after a redeploy would never receive {@code onServicesDiscovered()}
     * again (neither the core nor our reconciler re-discovers while the latch is true), so its poll loop would sit
     * on an empty channel map and silently never read. Disconnecting cleanly here means the next incarnation
     * starts from DISCONNECTED and runs a full fresh discover -> SERVICES_DISCOVERED -> polling resumes.
     */
    void close() {
        BTDevice dev = device;
        releaseNotifyListeners();
        if (dev != null) {
            try {
                if (dev.getConnected()) {
                    dev.disconnect();
                }
            } catch (RuntimeException e) {
                logger.debug("Error disconnecting {} on close", address, e);
            }
        }
        // Reset the openHAB-model state (clears services + the servicesDiscovered latch, nulls the handle).
        markDisconnected();
    }

    private static int mapProperties(GattCharPropertySet props) {
        int result = 0;
        if (props.isSet(GattCharPropertySet.Type.Broadcast)) {
            result |= BluetoothCharacteristic.PROPERTY_BROADCAST;
        }
        if (props.isSet(GattCharPropertySet.Type.Read)) {
            result |= BluetoothCharacteristic.PROPERTY_READ;
        }
        if (props.isSet(GattCharPropertySet.Type.WriteNoAck)) {
            result |= BluetoothCharacteristic.PROPERTY_WRITE_NO_RESPONSE;
        }
        if (props.isSet(GattCharPropertySet.Type.WriteWithAck)) {
            result |= BluetoothCharacteristic.PROPERTY_WRITE;
        }
        if (props.isSet(GattCharPropertySet.Type.Notify)) {
            result |= BluetoothCharacteristic.PROPERTY_NOTIFY;
        }
        if (props.isSet(GattCharPropertySet.Type.Indicate)) {
            result |= BluetoothCharacteristic.PROPERTY_INDICATE;
        }
        if (props.isSet(GattCharPropertySet.Type.AuthSignedWrite)) {
            result |= BluetoothCharacteristic.PROPERTY_SIGNED_WRITE;
        }
        if (props.isSet(GattCharPropertySet.Type.ExtProps)) {
            result |= BluetoothCharacteristic.PROPERTY_EXTENDED_PROPS;
        }
        return result;
    }

    /**
     * Direct-BT GATT characteristic notification listener; forwards notifications/indications to openHAB as
     * {@code CHARACTERISTIC_UPDATED} events. {@code @NonNullByDefault({})} because the Direct-BT base class
     * method parameters carry no null annotations, and the native peer is built lazily by its ctor (we
     * construct it only after the device is connected/GATT-resolved).
     */
    @NonNullByDefault({})
    private class DirectBTGattCharListener extends BTGattCharListener {
        private final UUID charUuid;

        DirectBTGattCharListener(UUID charUuid) {
            this.charUuid = charUuid;
        }

        @Override
        public void notificationReceived(BTGattChar charDecl, byte[] value, long timestamp) {
            forward(value);
        }

        @Override
        public void indicationReceived(BTGattChar charDecl, byte[] value, long timestamp, boolean confirmationSent) {
            forward(value);
        }

        private void forward(byte[] value) {
            BluetoothCharacteristic characteristic = null;
            for (BluetoothService s : getServices()) {
                BluetoothCharacteristic c = s.getCharacteristic(charUuid);
                if (c != null) {
                    characteristic = c;
                    break;
                }
            }
            if (characteristic != null) {
                notifyListeners(BluetoothEventType.CHARACTERISTIC_UPDATED, characteristic, value);
            }
        }
    }
}
