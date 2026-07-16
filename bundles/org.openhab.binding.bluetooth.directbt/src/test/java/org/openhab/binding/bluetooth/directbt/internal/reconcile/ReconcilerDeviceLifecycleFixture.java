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

import static org.openhab.binding.bluetooth.directbt.internal.reconcile.ReconcileTestSupport.*;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * {@link DeviceLifecycleFixture} over the current {@link DeviceReconciler}, wired exactly the way
 * {@code DirectBTBridgeHandler} wires it in production: events expedite the next act (fresh evidence bypasses
 * the act-backoff) and a disconnect event additionally marks the current connect attempt as failed.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
class ReconcilerDeviceLifecycleFixture implements DeviceLifecycleFixture {

    private final FakeDevicePort port = new FakeDevicePort();
    private final MutableClock clock = new MutableClock(START);
    private final AtomicBoolean scanActive = new AtomicBoolean(false);
    private final AtomicInteger resets = new AtomicInteger();
    private final DeviceReconciler reconciler = new DeviceReconciler(logger(), port, () -> !scanActive.get(),
            budget(clock), resets::incrementAndGet, clock);

    @Override
    public FakeDevicePort port() {
        return port;
    }

    @Override
    public void advance(long millis) {
        clock.advance(millis);
    }

    @Override
    public void tick() {
        reconciler.reconcile();
    }

    @Override
    public void fireConnectedEvent() {
        // Production: DirectBTBridgeHandler.deviceConnected -> expedite + requeue. Truth is scripted via port().
        reconciler.expediteNextAct();
    }

    @Override
    public void fireDisconnectedEvent() {
        // Production: deviceDisconnected -> note failed attempt + expedite + requeue.
        port.connectAttemptFailed = true;
        reconciler.expediteNextAct();
    }

    @Override
    public void fireHandleFoundEvent() {
        // Production: deviceFound -> wrapper gains the native handle; the event requeues an expedited pass.
        port.hasNative = true;
        reconciler.expediteNextAct();
    }

    @Override
    public void setScanActive(boolean active) {
        scanActive.set(active);
    }

    @Override
    public void setAdapterHealthy(boolean healthy) {
        // Production: the bridge tick pauses device reconcilers while the adapter phase is unhealthy and
        // unpauses them once it recovers (timers freeze across the pause).
        if (healthy) {
            reconciler.unpause();
        } else {
            reconciler.pause();
        }
    }

    @Override
    public boolean wantsDiscovery() {
        return reconciler.wantsDiscovery();
    }

    @Override
    public boolean needsConnectWindow() {
        return reconciler.needsConnection();
    }

    @Override
    public int adapterResetRequests() {
        return resets.get();
    }

    @Override
    public long nowMillis() {
        return clock.millis();
    }
}
