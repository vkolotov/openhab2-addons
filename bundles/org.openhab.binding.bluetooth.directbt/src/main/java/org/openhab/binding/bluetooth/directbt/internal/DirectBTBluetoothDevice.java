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

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

import org.direct_bt.BTDevice;
import org.direct_bt.BTGattChar;
import org.direct_bt.BTGattCharListener;
import org.direct_bt.BTGattService;
import org.direct_bt.EInfoReport;
import org.direct_bt.GattCharPropertySet;
import org.direct_bt.HCIStatusCode;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.bluetooth.BaseBluetoothDevice;
import org.openhab.binding.bluetooth.BluetoothAddress;
import org.openhab.binding.bluetooth.BluetoothCharacteristic;
import org.openhab.binding.bluetooth.BluetoothDescriptor;
import org.openhab.binding.bluetooth.BluetoothService;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.AdapterReconciler;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.DeviceReconciler;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.DevicePort;
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

    // Maps an openHAB characteristic UUID to the Direct-BT characteristic handle (populated on discovery).
    private final Map<UUID, BTGattChar> gattCharByUuid = new ConcurrentHashMap<>();
    // Active notification listeners per characteristic UUID, so we can unregister on disable/dispose.
    private final Map<UUID, BTGattCharListener> notifyListeners = new ConcurrentHashMap<>();

    public DirectBTBluetoothDevice(DirectBTBridgeHandler adapter, BluetoothAddress address) {
        super(adapter, address);
        this.bridge = adapter;
        this.executor = adapter.getExecutor();
        this.reconciler = new DeviceReconciler(logger, this,
                () -> {
                    AdapterReconciler ar = bridge.getAdapterReconciler();
                    return ar != null && ar.isScanOff();
                }, adapter.getResetBudget(), bridge::requestAdapterReset);
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
            return false;
        }
        bridge.requeueReconcile();
        // Return true (intent accepted) so the core's reconnect loop treats this as "connecting in progress" and
        // stops hammering; the reconciler drives the real state and getConnectionState() reflects native truth.
        return true;
    }

    @Override
    public boolean disconnect() {
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

    // --- DevicePort: the operations the DeviceReconciler drives (all polled native truth / idempotent) ----

    @Override
    public boolean isWanted() {
        return !getListeners().isEmpty();
    }

    @Override
    public boolean hasNativeDevice() {
        return device != null;
    }

    @Override
    public boolean isNativeConnected() {
        BTDevice dev = device;
        try {
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
    public void markConnected() {
        setConnectionState(ConnectionState.CONNECTED);
    }

    @Override
    public void markConnecting() {
        setConnectionState(ConnectionState.CONNECTING);
    }

    @Override
    public void markDisconnected() {
        releaseNotifyListeners();
        clearServices();
        gattCharByUuid.clear();
        // Drop the stale native handle: after a disconnect the BTDevice no longer represents a usable link, and a
        // reconnect attempt on the old handle is exactly the path that wedges (CSR COMMAND_DISALLOWED / a connectLE
        // that the controller silently never completes). Clearing it makes the device hasNativeDevice()==false, so
        // the discovery reconciler turns the scan back on and re-finds the device from a FRESH advertisement before
        // the next connect -- the same "trust the fresh frame, not a cached object" discipline the rest of the
        // stack relies on. updateBTDevice() installs the new handle when the scan rediscovers it.
        device = null;
        setConnectionState(ConnectionState.DISCONNECTED);
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
        clearServices();
        gattCharByUuid.clear();
        try {
            // The reconciler has already ensured the adapter scan is OFF (the controller rejects create-connection
            // while scanning) and owns the connect lifecycle, so no inline pre-connect cleanup is needed. A former
            // dev.disconnect() residue-clear here was ablated (2026-06-26, from-scratch CSR rebuild) and proven
            // redundant: connect succeeds first-try and holds dead-stable (45/45 reads, 0 disconnects) without it,
            // and the reconciler recovers from the CSR COMMAND_DISALLOWED/INTERNAL_TIMEOUT quirk on its own.
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
        BTGattChar gattChar = connectedChar(characteristic.getUuid());
        if (gattChar == null) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Characteristic not available (disconnected?): " + characteristic.getUuid()));
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                byte[] value = gattChar.readValue();
                if (value.length == 0) {
                    logger.debug("Direct-BT read of {} on {} returned empty value; suppressing update",
                            characteristic.getUuid(), address);
                    return value;
                }
                notifyListeners(BluetoothEventType.CHARACTERISTIC_UPDATED, characteristic, value);
                return value;
            } catch (RuntimeException e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<@Nullable Void> writeCharacteristic(BluetoothCharacteristic characteristic, byte[] value) {
        BTGattChar gattChar = connectedChar(characteristic.getUuid());
        if (gattChar == null) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Characteristic not available (disconnected?): " + characteristic.getUuid()));
        }
        // withResponse=true (acknowledged write) unless only write-without-response is supported.
        boolean withResponse = gattChar.getProperties().isSet(GattCharPropertySet.Type.WriteWithAck);
        return CompletableFuture.runAsync(() -> {
            try {
                if (!gattChar.writeValue(value, withResponse)) {
                    throw new java.util.concurrent.CompletionException(
                            new RuntimeException("writeValue returned false"));
                }
            } catch (RuntimeException e) {
                throw new java.util.concurrent.CompletionException(e);
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
        BTGattChar gattChar = connectedChar(charUuid);
        if (gattChar == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Characteristic not available (disconnected?): " + charUuid));
        }
        // Reserve the slot atomically before native registration so two concurrent callers can't both
        // register a native listener (the second put would orphan the first, making it unremovable).
        BTGattCharListener listener = new DirectBTGattCharListener(charUuid);
        if (notifyListeners.putIfAbsent(charUuid, listener) != null) {
            return CompletableFuture.completedFuture(null); // already enabled / being enabled
        }
        return CompletableFuture.runAsync(() -> {
            try {
                if (!gattChar.addCharListener(listener)) {
                    throw new java.util.concurrent.CompletionException(
                            new RuntimeException("addCharListener returned false"));
                }
                if (!gattChar.enableNotificationOrIndication(new boolean[2])) {
                    gattChar.removeCharListener(listener);
                    throw new java.util.concurrent.CompletionException(
                            new RuntimeException("enableNotificationOrIndication returned false"));
                }
            } catch (RuntimeException e) {
                notifyListeners.remove(charUuid, listener); // roll back the reservation on failure
                throw new java.util.concurrent.CompletionException(e);
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
                throw new java.util.concurrent.CompletionException(e);
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
