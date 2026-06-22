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
import java.util.concurrent.CompletableFuture;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.bluetooth.BaseBluetoothDevice;
import org.openhab.binding.bluetooth.BluetoothAddress;
import org.openhab.binding.bluetooth.BluetoothCharacteristic;
import org.openhab.binding.bluetooth.BluetoothDescriptor;

import org.direct_bt.BTDevice;
import org.direct_bt.EInfoReport;

/**
 * A {@link org.openhab.binding.bluetooth.BluetoothDevice} backed by a Direct-BT {@link BTDevice}.
 * <p>
 * NOTE: connect/GATT mapping is added in a follow-up step; for now this carries the Direct-BT device
 * handle and copies advertisement-derived fields (RSSI/name/tx-power/manufacturer/last-seen) into the
 * openHAB device model so adapter discovery and inactive-device cleanup work correctly.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
public class DirectBTBluetoothDevice extends BaseBluetoothDevice {

    private @Nullable BTDevice device;

    public DirectBTBluetoothDevice(DirectBTBridgeHandler adapter, BluetoothAddress address) {
        super(adapter, address);
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
                manData.keySet().stream().filter(java.util.Objects::nonNull).findFirst()
                        .ifPresent(id -> setManufacturerId(id & 0xFFFF));
            }
        }
    }

    @Nullable
    BTDevice getBTDevice() {
        return device;
    }

    // --- Connect / GATT operations -------------------------------------------------------------------
    // Skeleton: not yet implemented. These compile the bundle and let adapter discovery/RSSI work;
    // the Direct-BT BTDevice/BTGattChar mapping is added in the next step.

    @Override
    public boolean connect() {
        return false;
    }

    @Override
    public boolean disconnect() {
        return false;
    }

    @Override
    public boolean discoverServices() {
        return false;
    }

    @Override
    public CompletableFuture<byte[]> readCharacteristic(BluetoothCharacteristic characteristic) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("not yet implemented"));
    }

    @Override
    public CompletableFuture<@Nullable Void> writeCharacteristic(BluetoothCharacteristic characteristic, byte[] value) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("not yet implemented"));
    }

    @Override
    public boolean isNotifying(BluetoothCharacteristic characteristic) {
        return false;
    }

    @Override
    public CompletableFuture<@Nullable Void> enableNotifications(BluetoothCharacteristic characteristic) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("not yet implemented"));
    }

    @Override
    public CompletableFuture<@Nullable Void> disableNotifications(BluetoothCharacteristic characteristic) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("not yet implemented"));
    }

    @Override
    public boolean enableNotifications(BluetoothDescriptor descriptor) {
        return false;
    }

    @Override
    public boolean disableNotifications(BluetoothDescriptor descriptor) {
        return false;
    }
}
