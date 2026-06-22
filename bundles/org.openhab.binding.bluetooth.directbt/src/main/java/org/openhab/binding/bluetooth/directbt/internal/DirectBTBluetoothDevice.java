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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.bluetooth.BaseBluetoothDevice;
import org.openhab.binding.bluetooth.BluetoothAddress;
import org.openhab.binding.bluetooth.BluetoothCharacteristic;
import org.openhab.binding.bluetooth.BluetoothDescriptor;
import org.openhab.binding.bluetooth.BluetoothService;
import org.openhab.binding.bluetooth.notification.BluetoothConnectionStatusNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.direct_bt.BTDevice;
import org.direct_bt.BTGattChar;
import org.direct_bt.BTGattCharListener;
import org.direct_bt.BTGattService;
import org.direct_bt.EInfoReport;
import org.direct_bt.GattCharPropertySet;
import org.direct_bt.HCIStatusCode;

/**
 * A {@link org.openhab.binding.bluetooth.BluetoothDevice} backed by a Direct-BT {@link BTDevice}.
 * <p>
 * Carries the Direct-BT device handle, copies advertisement-derived fields into the openHAB model, and
 * maps connect / GATT service discovery / characteristic read-write-notify onto the Direct-BT API.
 * Connection-state transitions are driven by the adapter's {@code AdapterStatusListener} (forwarded from
 * {@link DirectBTBridgeHandler} via {@link #onConnected()} / {@link #onDisconnected()}); the blocking
 * Direct-BT read/write calls are run on the bridge's notification executor and surfaced as
 * {@link CompletableFuture}s.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
public class DirectBTBluetoothDevice extends BaseBluetoothDevice {

    private final Logger logger = LoggerFactory.getLogger(DirectBTBluetoothDevice.class);

    private final ExecutorService executor;

    private @Nullable BTDevice device;

    // Maps an openHAB characteristic UUID to the Direct-BT characteristic handle (populated on discovery).
    private final Map<UUID, BTGattChar> gattCharByUuid = new ConcurrentHashMap<>();
    // Active notification listeners per characteristic UUID, so we can unregister on disable/dispose.
    private final Map<UUID, BTGattCharListener> notifyListeners = new ConcurrentHashMap<>();

    public DirectBTBluetoothDevice(DirectBTBridgeHandler adapter, BluetoothAddress address) {
        super(adapter, address);
        this.executor = adapter.getExecutor();
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

    // --- Connection-state callbacks (forwarded from the adapter status listener) ---------------------

    /** Called by the bridge when Direct-BT reports this device connected. */
    void onConnected() {
        notifyListeners(BluetoothEventType.CONNECTION_STATE,
                new BluetoothConnectionStatusNotification(ConnectionState.CONNECTED));
    }

    /** Called by the bridge when Direct-BT reports this device ready (GATT resolved). */
    void onReady() {
        discoverServices();
    }

    /** Called by the bridge when Direct-BT reports this device disconnected. */
    void onDisconnected() {
        gattCharByUuid.clear();
        notifyListeners.clear();
        notifyListeners(BluetoothEventType.CONNECTION_STATE,
                new BluetoothConnectionStatusNotification(ConnectionState.DISCONNECTED));
    }

    // --- Connect / GATT operations -------------------------------------------------------------------

    @Override
    public boolean connect() {
        BTDevice dev = device;
        if (dev == null || dev.getConnected()) {
            return false;
        }
        notifyListeners(BluetoothEventType.CONNECTION_STATE,
                new BluetoothConnectionStatusNotification(ConnectionState.CONNECTING));
        try {
            HCIStatusCode status = dev.connectDefault();
            if (status != HCIStatusCode.SUCCESS) {
                logger.debug("Direct-BT connect to {} failed: {}", address, status);
                return false;
            }
            return true;
        } catch (RuntimeException e) {
            logger.debug("Direct-BT connect to {} threw", address, e);
            return false;
        }
    }

    @Override
    public boolean disconnect() {
        BTDevice dev = device;
        if (dev == null || !dev.getConnected()) {
            return false;
        }
        try {
            return dev.disconnect() == HCIStatusCode.SUCCESS;
        } catch (RuntimeException e) {
            logger.debug("Direct-BT disconnect from {} threw", address, e);
            return false;
        }
    }

    @Override
    public boolean discoverServices() {
        BTDevice dev = device;
        if (dev == null) {
            return false;
        }
        try {
            for (BTGattService gattService : dev.getGattServices()) {
                UUID serviceUuid = UUID.fromString(gattService.getUUID());
                if (getServices(serviceUuid) != null) {
                    continue; // already mapped
                }
                BluetoothService service = new BluetoothService(serviceUuid, true);
                for (BTGattChar gattChar : gattService.getChars()) {
                    UUID charUuid = UUID.fromString(gattChar.getUUID());
                    BluetoothCharacteristic characteristic = new BluetoothCharacteristic(charUuid,
                            mapProperties(gattChar.getProperties()));
                    service.addCharacteristic(characteristic);
                    gattCharByUuid.put(charUuid, gattChar);
                }
                addService(service);
            }
        } catch (RuntimeException e) {
            logger.debug("Direct-BT service discovery for {} failed", address, e);
            return false;
        }
        if (!getServices().isEmpty()) {
            notifyListeners(BluetoothEventType.SERVICES_DISCOVERED);
        }
        return true;
    }

    @Override
    public CompletableFuture<byte[]> readCharacteristic(BluetoothCharacteristic characteristic) {
        BTGattChar gattChar = gattCharByUuid.get(characteristic.getUuid());
        if (gattChar == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Characteristic not found: "
                    + characteristic.getUuid()));
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                return gattChar.readValue();
            } catch (RuntimeException e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<@Nullable Void> writeCharacteristic(BluetoothCharacteristic characteristic,
            byte[] value) {
        BTGattChar gattChar = gattCharByUuid.get(characteristic.getUuid());
        if (gattChar == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Characteristic not found: "
                    + characteristic.getUuid()));
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
        BTGattChar gattChar = gattCharByUuid.get(charUuid);
        if (gattChar == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Characteristic not found: " + charUuid));
        }
        if (notifyListeners.containsKey(charUuid)) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(() -> {
            try {
                BTGattCharListener listener = new DirectBTGattCharListener(charUuid);
                if (!gattChar.addCharListener(listener)) {
                    throw new java.util.concurrent.CompletionException(
                            new RuntimeException("addCharListener returned false"));
                }
                boolean[] enabledState = new boolean[2];
                if (!gattChar.enableNotificationOrIndication(enabledState)) {
                    gattChar.removeCharListener(listener);
                    throw new java.util.concurrent.CompletionException(
                            new RuntimeException("enableNotificationOrIndication returned false"));
                }
                notifyListeners.put(charUuid, listener);
            } catch (RuntimeException e) {
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

    /** Best-effort release of native listeners; called from the bridge on device disposal. */
    void close() {
        BTDevice dev = device;
        for (Map.Entry<UUID, BTGattCharListener> entry : notifyListeners.entrySet()) {
            BTGattChar gattChar = gattCharByUuid.get(entry.getKey());
            try {
                if (gattChar != null) {
                    gattChar.removeCharListener(entry.getValue());
                }
            } catch (RuntimeException e) {
                logger.debug("Error removing char listener on close for {}", address, e);
            }
        }
        notifyListeners.clear();
        gattCharByUuid.clear();
        if (dev != null && dev.getConnected()) {
            try {
                dev.disconnect();
            } catch (RuntimeException e) {
                logger.debug("Error disconnecting {} on close", address, e);
            }
        }
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
