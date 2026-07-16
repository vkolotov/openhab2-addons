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

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.direct_bt.HCIStatusCode;
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
    private final DeviceProcedureRunner runner = new DeviceProcedureRunner(actor, this::createProcedure);
    private final AtomicInteger resets = new AtomicInteger();

    private boolean scanActive;
    private boolean adapterHealthy = true;
    private boolean pendingConnectLease;
    private boolean gattProcedureActive;
    private long settleDueAt = -1;
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
        if (pendingConnectLease && !scanActive) {
            pendingConnectLease = false;
            runner.submit(new DeviceEvent.ConnectLeaseGranted(actor.diagnostics().generation()));
            drainEffects();
        }
        if (settleDueAt >= 0 && clock.millis() >= settleDueAt) {
            settleDueAt = -1;
            runner.submit(new DeviceEvent.LinkSettleTimerExpired(actor.diagnostics().generation()));
            drainEffects();
        }
        if (shouldStartResolveGatt()) {
            gattProcedureActive = true;
            runner.start(new ResolveGattProcedure(GATT_DEADLINE_MS), "contract-gatt");
            drainEffects();
        }
        if (shouldStartConnect()) {
            runner.start(new ConnectProcedure(CONNECT_DEADLINE_MS), "contract-connect");
            drainEffects();
        }
        runner.tick();
        drainEffects();
    }

    @Override
    public void fireConnectedEvent() {
        port.markConnected();
        runner.submit(new DeviceEvent.NativeConnected(actor.diagnostics().generation()));
        drainEffects();
    }

    @Override
    public void fireDisconnectedEvent() {
        port.connectAttemptFailed = true;
        if (port.isPairing()) {
            return;
        }
        runner.submit(new DeviceEvent.ConnectFailed(actor.diagnostics().generation(), "DISCONNECTED",
                port.hasStalePairing()));
        drainEffects();
        markDisconnectedAndBackoff();
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

    private void drainEffects() {
        List<DeviceEffect> effects = runner.drainEffects();
        while (!effects.isEmpty()) {
            for (DeviceEffect effect : effects) {
                execute(effect);
            }
            effects = runner.drainEffects();
        }
    }

    private void execute(DeviceEffect effect) {
        if (effect.generation() != actor.diagnostics().generation()) {
            return;
        }
        String operation = effect.operation();
        if (ConnectProcedure.EFFECT_REQUEST_CONNECT_LEASE.equals(operation)) {
            if (scanActive) {
                pendingConnectLease = true;
            } else {
                runner.submit(new DeviceEvent.ConnectLeaseGranted(effect.generation()));
            }
            return;
        }
        if (ConnectProcedure.EFFECT_CONNECT_LE.equals(operation)) {
            port.markConnecting();
            HCIStatusCode result = port.connectNative();
            if (result == HCIStatusCode.SUCCESS) {
                connectRejections = 0;
            } else {
                connectRejections++;
                if (connectRejections >= RESET_AFTER_REJECTIONS && resets.get() == 0) {
                    resets.incrementAndGet();
                }
                runner.submit(new DeviceEvent.ConnectFailed(effect.generation(), result.name(), false));
                markDisconnectedAndBackoff();
            }
            return;
        }
        if (ConnectProcedure.EFFECT_DISCONNECT_NATIVE.equals(operation)) {
            port.disconnectNative();
            if (port.hasStalePairing() && actor.diagnostics().state() == DeviceActorState.BACKING_OFF) {
                port.clearStalePairing();
            }
            markDisconnectedAndBackoff();
            return;
        }
        if (ConnectProcedure.EFFECT_CLEAR_STALE_PAIRING.equals(operation)) {
            port.clearStalePairing();
            return;
        }
        if (SettleLinkProcedure.EFFECT_SCHEDULE_LINK_SETTLE_TIMER.equals(operation)) {
            settleDueAt = clock.millis() + SETTLE_DELAY_MS;
            return;
        }
        if (ResolveGattProcedure.EFFECT_RESOLVE_GATT.equals(operation)) {
            port.resolveGatt();
            if (port.isGattResolved()) {
                runner.submit(new DeviceEvent.GattResolveSucceeded(effect.generation()));
            }
            return;
        }
        if (ResolveGattProcedure.EFFECT_START_SUBSCRIBE_PROCEDURE.equals(operation)) {
            gattProcedureActive = false;
            port.markConnected();
        }
    }

    private void markDisconnectedAndBackoff() {
        port.markDisconnected();
        pendingConnectLease = false;
        gattProcedureActive = false;
        settleDueAt = -1;
        backoffUntil = clock.millis() + BACKOFF_MS;
    }
}
