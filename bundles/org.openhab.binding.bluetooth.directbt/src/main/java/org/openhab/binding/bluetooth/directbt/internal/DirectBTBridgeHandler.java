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

import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.bluetooth.AbstractBluetoothBridgeHandler;
import org.openhab.binding.bluetooth.BluetoothAddress;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.direct_bt.AdapterStatusListener;
import org.direct_bt.BTAdapter;
import org.direct_bt.BTDevice;
import org.direct_bt.BTFactory;
import org.direct_bt.BTManager;
import org.direct_bt.BTManager.ChangedAdapterSetListener;
import org.direct_bt.BTMode;
import org.direct_bt.DiscoveryPolicy;
import org.direct_bt.EIRDataTypeSet;
import org.direct_bt.HCIStatusCode;
import org.direct_bt.ScanType;

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

    // Discovery scan parameters, matching the Direct-BT reference defaults.
    private static final short LE_SCAN_INTERVAL = (short) 24;
    private static final short LE_SCAN_WINDOW = (short) 24;
    private static final byte FILTER_POLICY = (byte) 0;
    private static final boolean FILTER_DUP = true;

    private final Logger logger = LoggerFactory.getLogger(DirectBTBridgeHandler.class);

    private @Nullable BluetoothAddress adapterAddress;
    private @Nullable BTManager manager;
    private @Nullable BTAdapter adapter;
    private @Nullable ScheduledFuture<?> initJob;
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
            onDeviceFound(device);
            return false; // false = keep the device in discovery (do not take exclusive ownership)
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

    public DirectBTBridgeHandler(Bridge bridge) {
        super(bridge);
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
            return;
        }
        try {
            // Direct-BT's jar-cache native loader is unreliable inside OSGi (can't locate its own jar).
            // The loader extracts the bundled libs to java.library.path (and disables the jar-cache) so
            // Direct-BT's loader finds them there by basename.
            DirectBTNativesLoader.extractNatives();

            BTManager mgr = BTFactory.getDirectBTManager();
            this.manager = mgr;
            // Registering the listener immediately invokes adapterAdded() for adapters already present.
            mgr.addChangedAdapterSetListener(changedAdapterSetListener);
            managerReady = true;
            // Adapter arrival is event-driven via the listener from here on; the retry poll is no longer
            // needed (it only existed to get the manager up).
            cancelInitJob();
        } catch (UnsupportedOperationException e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, e.getMessage());
            cancelInitJob();
        } catch (Exception e) {
            logger.debug("Direct-BT initialization failed, will retry", e);
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, "Direct-BT init failed");
        }
    }

    /** Brings up the matching adapter: initialize (claim HCI user channel + power on) + scan. */
    private synchronized void onAdapterAdded(BTAdapter added) {
        BluetoothAddress wanted = adapterAddress;
        if (disposed || wanted == null || adapter != null) {
            return;
        }
        String mac = added.getAddressAndType().address.toString().toUpperCase();
        if (!mac.equals(wanted.toString())) {
            return; // not our adapter
        }
        try {
            logger.debug("Direct-BT onAdapterAdded {} pre-state: initialized={} powered={}", wanted,
                    added.isInitialized(), added.isPowered());
            // Bring the adapter to a powered state FIRST. Three cases:
            //  - never initialized  -> initialize() (power-cycles + powers on)
            //  - initialized but off -> initialize() returns FAILED and setPowered() won't recover it, so
            //                           reset() (brings the device up from standby into a POWERED state)
            //  - already powered     -> nothing to do
            // Order matters: addStatusListener() / startDiscovery() must come AFTER the adapter is
            // initialized & powered. Calling addStatusListener() on a freshly-replayed, not-yet-initialized
            // adapter crashes the native layer with a null-reference (jaulib helper_jni.hpp:512).
            if (!added.isPowered()) {
                HCIStatusCode rc;
                if (!added.isInitialized()) {
                    rc = added.initialize(BTMode.DUAL, true);
                    logger.debug("Direct-BT adapter {} initialize: {} (powered={})", wanted, rc, added.isPowered());
                } else {
                    rc = added.reset();
                    logger.debug("Direct-BT adapter {} reset: {} (powered={})", wanted, rc, added.isPowered());
                    if (rc == HCIStatusCode.SUCCESS && !added.isPowered()) {
                        added.setPowered(true);
                    }
                }
                if (rc != HCIStatusCode.SUCCESS) {
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                            "Adapter power-up failed: " + rc + " (is bluetoothd disabled for this adapter?)");
                    return;
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
            // otherwise.
            added.stopDiscovery();
            // Use the explicit scan-parameter form (matching the Direct-BT reference defaults): the short
            // 2-arg overload can return INTERNAL_FAILURE on some controllers/states.
            HCIStatusCode res = added.startDiscovery(null, DiscoveryPolicy.PAUSE_CONNECTED_UNTIL_READY, true,
                    LE_SCAN_INTERVAL, LE_SCAN_WINDOW, FILTER_POLICY, FILTER_DUP);
            logger.debug("Direct-BT adapter {} startDiscovery: {} (powered={})", wanted, res, added.isPowered());
            if (res != HCIStatusCode.SUCCESS) {
                added.removeStatusListener(listener);
                statusListener = null;
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                        "startDiscovery failed: " + res);
                return;
            }
            this.adapter = added;
            updateStatus(ThingStatus.ONLINE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            logger.debug("Failed to bring up Direct-BT adapter {}", wanted, e);
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, "Adapter bring-up failed");
        }
    }

    private synchronized void onAdapterRemoved(BTAdapter removed) {
        if (adapter == removed) {
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

    private void onDeviceFound(BTDevice btDevice) {
        if (disposed) {
            return;
        }
        String mac = btDevice.getAddressAndType().address.toString().toUpperCase();
        DirectBTBluetoothDevice device = getDevice(new BluetoothAddress(mac));
        device.updateBTDevice(btDevice);
        deviceDiscovered(device);
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
        synchronized (this) {
            BTManager localManager = manager;
            if (localManager != null) {
                try {
                    localManager.removeChangedAdapterSetListener(changedAdapterSetListener);
                } catch (RuntimeException e) {
                    logger.debug("Error removing Direct-BT adapter-set listener on dispose", e);
                }
            }
            detachAdapter();
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
}
