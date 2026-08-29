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
package org.openhab.binding.bluetooth.directbt.internal.reconcile.event;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.procedure.DeviceProcedureName;

/**
 * Control-plane event consumed by the device actor. Events that belong to one connection attempt carry the actor
 * generation; adapter and intent events are global.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
public interface DeviceEvent {
    String kind();

    default boolean generationScoped() {
        return false;
    }

    default long generation() {
        return -1;
    }

    final class WantedOnline implements DeviceEvent {
        @Override
        public String kind() {
            return "WantedOnline";
        }
    }

    final class WantedOffline implements DeviceEvent {
        @Override
        public String kind() {
            return "WantedOffline";
        }
    }

    final class ConnectLeaseGranted implements DeviceEvent {
        private final long generation;

        public ConnectLeaseGranted(long generation) {
            this.generation = generation;
        }

        @Override
        public String kind() {
            return "ConnectLeaseGranted";
        }

        @Override
        public boolean generationScoped() {
            return true;
        }

        @Override
        public long generation() {
            return generation;
        }
    }

    final class NativeConnected implements DeviceEvent {
        private final long generation;

        public NativeConnected(long generation) {
            this.generation = generation;
        }

        @Override
        public String kind() {
            return "NativeConnected";
        }

        @Override
        public boolean generationScoped() {
            return true;
        }

        @Override
        public long generation() {
            return generation;
        }
    }

    final class PairingStarted implements DeviceEvent {
        private final long generation;

        public PairingStarted(long generation) {
            this.generation = generation;
        }

        @Override
        public String kind() {
            return "PairingStarted";
        }

        @Override
        public boolean generationScoped() {
            return true;
        }

        @Override
        public long generation() {
            return generation;
        }
    }

    final class PairingEnded implements DeviceEvent {
        private final long generation;

        public PairingEnded(long generation) {
            this.generation = generation;
        }

        @Override
        public String kind() {
            return "PairingEnded";
        }

        @Override
        public boolean generationScoped() {
            return true;
        }

        @Override
        public long generation() {
            return generation;
        }
    }

    final class ConnectFailed implements DeviceEvent {
        private final long generation;
        private final String reason;
        private final boolean staleBondSuspected;

        public ConnectFailed(long generation, String reason, boolean staleBondSuspected) {
            this.generation = generation;
            this.reason = reason;
            this.staleBondSuspected = staleBondSuspected;
        }

        @Override
        public String kind() {
            return "ConnectFailed";
        }

        @Override
        public boolean generationScoped() {
            return true;
        }

        @Override
        public long generation() {
            return generation;
        }

        public String reason() {
            return reason;
        }

        public boolean staleBondSuspected() {
            return staleBondSuspected;
        }
    }

    final class LinkSettleTimerExpired implements DeviceEvent {
        private final long generation;

        public LinkSettleTimerExpired(long generation) {
            this.generation = generation;
        }

        @Override
        public String kind() {
            return "LinkSettleTimerExpired";
        }

        @Override
        public boolean generationScoped() {
            return true;
        }

        @Override
        public long generation() {
            return generation;
        }
    }

    /**
     * A re-attempt request for an in-progress RESOLVE_GATT procedure: the reconciler paces retries (one per
     * reconcile tick, on the reconcile tick) and the procedure answers by re-emitting its
     * resolve effect.
     */
    final class GattResolveRequested implements DeviceEvent {
        private final long generation;

        public GattResolveRequested(long generation) {
            this.generation = generation;
        }

        @Override
        public String kind() {
            return "GattResolveRequested";
        }

        @Override
        public boolean generationScoped() {
            return true;
        }

        @Override
        public long generation() {
            return generation;
        }
    }

    final class GattResolveSucceeded implements DeviceEvent {
        private final long generation;

        public GattResolveSucceeded(long generation) {
            this.generation = generation;
        }

        @Override
        public String kind() {
            return "GattResolveSucceeded";
        }

        @Override
        public boolean generationScoped() {
            return true;
        }

        @Override
        public long generation() {
            return generation;
        }
    }

    final class GattResolveFailed implements DeviceEvent {
        private final long generation;
        private final String reason;

        public GattResolveFailed(long generation, String reason) {
            this.generation = generation;
            this.reason = reason;
        }

        @Override
        public String kind() {
            return "GattResolveFailed";
        }

        @Override
        public boolean generationScoped() {
            return true;
        }

        @Override
        public long generation() {
            return generation;
        }

        public String reason() {
            return reason;
        }
    }

    final class ProcedureDeadlineExpired implements DeviceEvent {
        private final long generation;
        private final DeviceProcedureName procedure;
        private final long elapsedMs;

        public ProcedureDeadlineExpired(long generation, DeviceProcedureName procedure, long elapsedMs) {
            this.generation = generation;
            this.procedure = procedure;
            this.elapsedMs = elapsedMs;
        }

        @Override
        public String kind() {
            return "ProcedureDeadlineExpired";
        }

        @Override
        public boolean generationScoped() {
            return true;
        }

        @Override
        public long generation() {
            return generation;
        }

        public DeviceProcedureName procedure() {
            return procedure;
        }

        public long elapsedMs() {
            return elapsedMs;
        }
    }

    final class NativeEffectCompleted implements DeviceEvent {
        private final long generation;
        private final DeviceEffectOperation operation;
        private final String result;

        public NativeEffectCompleted(long generation, DeviceEffectOperation operation, String result) {
            this.generation = generation;
            this.operation = operation;
            this.result = result;
        }

        @Override
        public String kind() {
            return "NativeEffectCompleted";
        }

        @Override
        public boolean generationScoped() {
            return true;
        }

        @Override
        public long generation() {
            return generation;
        }

        public DeviceEffectOperation operation() {
            return operation;
        }

        public String result() {
            return result;
        }
    }

    final class AdapterResetStarted implements DeviceEvent {
        private final long adapterGeneration;

        public AdapterResetStarted(long adapterGeneration) {
            this.adapterGeneration = adapterGeneration;
        }

        @Override
        public String kind() {
            return "AdapterResetStarted";
        }

        public long adapterGeneration() {
            return adapterGeneration;
        }
    }

    final class AdapterResetCompleted implements DeviceEvent {
        private final long adapterGeneration;
        private final @Nullable String result;

        public AdapterResetCompleted(long adapterGeneration, @Nullable String result) {
            this.adapterGeneration = adapterGeneration;
            this.result = result;
        }

        @Override
        public String kind() {
            return "AdapterResetCompleted";
        }

        public long adapterGeneration() {
            return adapterGeneration;
        }

        @Nullable
        public String result() {
            return result;
        }
    }
}
