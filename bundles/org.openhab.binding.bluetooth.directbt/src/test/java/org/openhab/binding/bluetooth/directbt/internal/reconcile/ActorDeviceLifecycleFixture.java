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

import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * {@link DeviceLifecycleFixture} over the actor/procedure model. This is intentionally a test-side effect runner:
 * procedures still emit data-only effects, and this fixture applies those effects to {@link FakeDevicePort}.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
class ActorDeviceLifecycleFixture implements DeviceLifecycleFixture {
    private static final long CONNECT_DEADLINE_MS = 15_000;
    private static final long SETTLE_DEADLINE_MS = 5_000;
    private static final long GATT_DEADLINE_MS = 120_000;
    private static final long SETTLE_DELAY_MS = 2_000;
    private static final long BACKOFF_MS = 2_000;
    private static final int RESET_AFTER_REJECTIONS = 3;

    private final FakeDevicePort port = new FakeDevicePort();
    private final MutableClock clock = new MutableClock(ReconcileTestSupport.START);
    private final DeviceActor actor = new DeviceActor("test-device", ReconcileTestSupport.logger(), clock);
    private boolean scanActive;
    private final SettleTimerEffectExecutor settleTimerExecutor = new SettleTimerEffectExecutor(() -> clock.millis(),
            SETTLE_DELAY_MS);
    private final DeviceActorRuntime runtime = new DeviceActorRuntime(actor, this::createProcedure, () -> !scanActive,
            port, this::observeRuntimeEvent, settleTimerExecutor, new DeviceBackoffPolicy(port), this::recordBackoff);
    private final AtomicInteger resets = new AtomicInteger();

    private boolean adapterHealthy = true;
    private boolean gattProcedureActive;
    private long backoffUntil = -1;
    private int connectRejections;

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
        if (!adapterHealthy) {
            return;
        }
        if (!port.isWanted()) {
            handleUnwanted();
            return;
        }
        if (port.isFlagConnected() && !port.isNativeConnected()) {
            markDisconnectedAndBackoff();
            return;
        }
        if (shouldStartResolveGatt()) {
            gattProcedureActive = true;
            runtime.start(new ResolveGattProcedure(GATT_DEADLINE_MS), "contract-gatt");
            drainRuntimeEffects();
        }
        if (shouldStartConnect()) {
            runtime.start(new ConnectProcedure(CONNECT_DEADLINE_MS), "contract-connect");
            drainRuntimeEffects();
        }
        runtime.tick();
        drainRuntimeEffects();
    }

    @Override
    public void fireConnectedEvent() {
        connectRejections = 0;
        port.markConnected();
        runtime.submit(new DeviceEvent.NativeConnected(actor.diagnostics().generation()));
        drainRuntimeEffects();
    }

    @Override
    public void fireDisconnectedEvent() {
        port.connectAttemptFailed = true;
        if (port.isPairing()) {
            return;
        }
        runtime.submit(new DeviceEvent.ConnectFailed(actor.diagnostics().generation(), "DISCONNECTED",
                port.hasStalePairing()));
        drainRuntimeEffects();
        if (runtime.diagnostics().state() != DeviceActorState.BACKING_OFF) {
            markDisconnectedAndBackoff();
        }
    }

    @Override
    public void fireHandleFoundEvent() {
        port.hasNative = true;
    }

    @Override
    public void setScanActive(boolean active) {
        scanActive = active;
    }

    @Override
    public void setAdapterHealthy(boolean healthy) {
        adapterHealthy = healthy;
    }

    @Override
    public boolean wantsDiscovery() {
        return port.isWanted() && !port.hasNativeDevice();
    }

    @Override
    public boolean needsConnectWindow() {
        return port.isWanted() && port.hasNativeDevice() && !port.isNativeConnected() && !port.isFlagConnected();
    }

    @Override
    public int adapterResetRequests() {
        return resets.get();
    }

    @Override
    public long nowMillis() {
        return clock.millis();
    }

    private @Nullable DeviceProcedure createProcedure(DeviceProcedureName procedureName) {
        if (procedureName == DeviceProcedureName.SETTLE_LINK) {
            return new SettleLinkProcedure(SETTLE_DEADLINE_MS);
        }
        if (procedureName == DeviceProcedureName.RESOLVE_GATT) {
            return new ResolveGattProcedure(GATT_DEADLINE_MS);
        }
        return null;
    }

    private boolean shouldStartConnect() {
        return port.isWanted() && port.hasNativeDevice() && !port.isNativeConnected() && !port.isFlagConnected()
                && !port.isFlagConnecting() && clock.millis() >= backoffUntil;
    }

    private boolean shouldStartResolveGatt() {
        return !gattProcedureActive && port.isWanted() && port.hasNativeDevice() && port.isNativeConnected()
                && port.isFlagConnected() && !port.isGattResolved() && port.isGattResolving();
    }

    private void handleUnwanted() {
        if (port.isNativeConnected() || port.isFlagConnected() || port.isFlagConnecting()) {
            port.disconnectNative();
            port.markDisconnected();
        }
    }

    private void drainRuntimeEffects() {
        for (DeviceEffect effect : runtime.drainUnhandledEffects()) {
            executeUnhandled(effect);
        }
    }

    private void executeUnhandled(DeviceEffect effect) {
        String operation = effect.operation();
        if (ResolveGattProcedure.EFFECT_START_SUBSCRIBE_PROCEDURE.equals(operation)) {
            gattProcedureActive = false;
            port.markConnected();
        }
    }

    private void observeRuntimeEvent(DeviceEvent event) {
        if (event instanceof DeviceEvent.ConnectFailed) {
            connectRejections++;
            if (connectRejections >= RESET_AFTER_REJECTIONS && resets.get() == 0) {
                resets.incrementAndGet();
            }
        }
    }

    private void markDisconnectedAndBackoff() {
        port.markDisconnected();
        recordBackoff(actor.diagnostics());
    }

    private void recordBackoff(DeviceActorDiagnostics diagnostics) {
        gattProcedureActive = false;
        backoffUntil = clock.millis() + BACKOFF_MS;
    }
}
