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

import org.direct_bt.HCIStatusCode;
import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * An in-memory {@link DevicePort} that models the native device the way the controller really behaves, so the
 * {@link DeviceReconciler} tests exercise the real state machine rather than a fixed snapshot:
 * <ul>
 * <li>{@link #connectNative()} returns a scripted status; on SUCCESS it does NOT flip {@link #nativeConnected}
 * immediately (Direct-BT commands are "accepted, not done") — the test calls {@link #settleNativeConnected()}
 * to simulate the later {@code deviceConnected} that the reconciler must observe, not assume.</li>
 * <li>{@link #markDisconnected()} nulls the native handle ({@link #hasNative} -> false), mirroring the real
 * "drop the stale handle so the device is re-found from a fresh advertisement" behaviour.</li>
 * <li>{@link #silentDrop()} clears native truth WITHOUT touching our flags — the defining failure of this
 * project (a controller-side ACL drop that fires no {@code deviceDisconnected} event).</li>
 * </ul>
 * Every corrective action increments a counter so tests can assert exactly what the reconciler did.
 * <p>
 * This is a hand-written <em>fake</em> rather than a Mockito mock on purpose: the reconciler mutates this
 * collaborator through callbacks (e.g. {@code markDisconnected()}) and then re-observes it on the next tick, so
 * the double must hold real, evolving state. A mock would need a {@code thenAnswer} over a backing state object
 * for every getter plus a {@code doAnswer} for every mutator — i.e. it would re-implement this fake with more
 * ceremony. Stateless collaborators ({@code BTAdapter}, {@code BTDevice}) are mocked with Mockito; this stateful
 * one is a fake. Do not "consistency-refactor" it into a mock.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
class FakeDevicePort implements DevicePort {

    // --- native + flag truth (test sets the scenario, reconciler drives it) ---
    boolean wanted;
    boolean hasNative;
    boolean nativeConnected;
    boolean gattResolved;
    boolean flagConnected;
    boolean flagConnecting;
    // Whether an SMP negotiation is currently in progress (true while setConnSecurityAuto iterates the ladder).
    boolean pairing;

    // --- scripted connectNative() result ---
    HCIStatusCode connectResult = HCIStatusCode.SUCCESS;

    // --- call counters ---
    int connectNativeCalls;
    int disconnectNativeCalls;
    int markConnectedCalls;
    int markConnectingCalls;
    int markDisconnectedCalls;
    int resolveGattCalls;

    @Override
    public boolean isWanted() {
        return wanted;
    }

    @Override
    public boolean hasNativeDevice() {
        return hasNative;
    }

    @Override
    public boolean isNativeConnected() {
        return nativeConnected;
    }

    @Override
    public boolean isGattResolved() {
        return gattResolved;
    }

    @Override
    public boolean isFlagConnected() {
        return flagConnected;
    }

    @Override
    public boolean isFlagConnecting() {
        return flagConnecting;
    }

    @Override
    public boolean isPairing() {
        return pairing;
    }

    @Override
    public void markConnected() {
        markConnectedCalls++;
        flagConnected = true;
        flagConnecting = false;
    }

    @Override
    public void markConnecting() {
        markConnectingCalls++;
        flagConnecting = true;
        flagConnected = false;
    }

    @Override
    public void markDisconnected() {
        markDisconnectedCalls++;
        // Real DirectBTBluetoothDevice.markDisconnected() clears the native handle so the device is re-found
        // from a fresh advert instead of reconnecting a stale handle; model that here.
        hasNative = false;
        nativeConnected = false;
        gattResolved = false;
        flagConnected = false;
        flagConnecting = false;
    }

    @Override
    public HCIStatusCode connectNative() {
        connectNativeCalls++;
        return connectResult;
    }

    @Override
    public void disconnectNative() {
        disconnectNativeCalls++;
        nativeConnected = false;
    }

    @Override
    public void resolveGatt() {
        resolveGattCalls++;
        gattResolved = true;
    }

    @Override
    public String id() {
        return "test-device";
    }

    // --- test-side scenario helpers ---

    /** Simulate the controller completing a previously-accepted create-connection (the later deviceConnected). */
    void settleNativeConnected() {
        nativeConnected = true;
    }

    /** Simulate a silent controller-side ACL drop: native link gone, but NO event, so flags still lie CONNECTED. */
    void silentDrop() {
        nativeConnected = false;
        gattResolved = false;
        // hasNative and flagConnected intentionally left as-is: the whole point is the reconciler must notice
        // via polling that native != flag, since no deviceDisconnected callback arrived.
    }
}
