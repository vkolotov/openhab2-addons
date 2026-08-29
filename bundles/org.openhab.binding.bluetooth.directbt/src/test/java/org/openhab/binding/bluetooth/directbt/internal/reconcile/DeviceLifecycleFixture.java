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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.adapter.*;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.device.*;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.effect.*;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.event.*;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.port.*;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.procedure.*;

/**
 * Implementation-neutral driver for the device connection lifecycle, so the SAME behavioural contract
 * ({@link DeviceLifecycleContract}) runs against both the current {@link DeviceReconciler} and the future
 * actor/procedure implementation. The contract asserts EFFECTS on the {@link FakeDevicePort} and timing WINDOWS — never
 * internal mechanics — which is what makes it portable across implementations.
 *
 * Semantics:
 * <ul>
 * <li>{@link #tick()} gives the implementation one convergence opportunity (a reconcile pass today; an actor
 * queue drain + due-timer firing later). Implementations may do nothing on a tick (e.g. while backing off);
 * the contract only constrains WHAT eventually happens and WITHIN which window.</li>
 * <li>{@link #fireConnectedEvent()} / {@link #fireDisconnectedEvent()} / {@link #fireHandleFoundEvent()}
 * deliver transport events (fresh evidence). Events never mutate scripted native truth by themselves —
 * script truth via {@link #port()} first, then fire the event that would accompany it. Exception:
 * {@code fireHandleFoundEvent} sets {@code port().hasNative} because "a handle exists" IS the event's
 * payload in both implementations.</li>
 * <li>Time only moves via {@link #advance(long)} (virtual clock; no sleeps).</li>
 * </ul>
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
interface DeviceLifecycleFixture {

    /** The scripted native device + effect counters shared by both implementations. */
    FakeDevicePort port();

    /** Advance the virtual clock. */
    void advance(long millis);

    /** One convergence opportunity. */
    void tick();

    /** Transport reported the native connection established (evidence; script truth via port() first). */
    void fireConnectedEvent();

    /** Transport reported a native disconnect/establishment failure for the current attempt. */
    void fireDisconnectedEvent();

    /** Discovery surfaced a native handle for this device. */
    void fireHandleFoundEvent();

    /** Script the adapter scan state the implementation observes (true = LE scan running). */
    void setScanActive(boolean active);

    /** Script adapter health; while unhealthy the device must make no radio progress and freeze timers. */
    void setAdapterHealthy(boolean healthy);

    /** @return whether the implementation currently asks for discovery/scan to find this device. */
    boolean wantsDiscovery();

    /** @return whether the implementation currently asks for a connect window (handle held, link wanted). */
    boolean needsConnectWindow();

    /** @return how many adapter resets the implementation has requested so far. */
    int adapterResetRequests();

    /** @return the virtual clock's current epoch millis. */
    long nowMillis();
}
