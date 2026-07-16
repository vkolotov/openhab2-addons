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
import org.eclipse.jdt.annotation.Nullable;

/**
 * Control-plane event consumed by the device actor. Events that belong to one connection attempt carry the actor
 * generation; adapter and intent events are global.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
interface DeviceEvent {
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

        ConnectLeaseGranted(long generation) {
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

        NativeConnected(long generation) {
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

        PairingStarted(long generation) {
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

        PairingEnded(long generation) {
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

        ConnectFailed(long generation, String reason, boolean staleBondSuspected) {
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

        String reason() {
            return reason;
        }

        boolean staleBondSuspected() {
            return staleBondSuspected;
        }
    }

    final class LinkSettleTimerExpired implements DeviceEvent {
        private final long generation;

        LinkSettleTimerExpired(long generation) {
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

    final class GattResolveSucceeded implements DeviceEvent {
        private final long generation;

        GattResolveSucceeded(long generation) {
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

        GattResolveFailed(long generation, String reason) {
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

        String reason() {
            return reason;
        }
    }

    final class ProcedureDeadlineExpired implements DeviceEvent {
        private final long generation;
        private final DeviceProcedureName procedure;
        private final long elapsedMs;

        ProcedureDeadlineExpired(long generation, DeviceProcedureName procedure, long elapsedMs) {
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

        DeviceProcedureName procedure() {
            return procedure;
        }

        long elapsedMs() {
            return elapsedMs;
        }
    }

    final class NativeEffectCompleted implements DeviceEvent {
        private final long generation;
        private final String operation;
        private final String result;

        NativeEffectCompleted(long generation, String operation, String result) {
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

        String operation() {
            return operation;
        }

        String result() {
            return result;
        }
    }

    final class NativeOperationHung implements DeviceEvent {
        private final long generation;
        private final String operation;
        private final long elapsedMs;

        NativeOperationHung(long generation, String operation, long elapsedMs) {
            this.generation = generation;
            this.operation = operation;
            this.elapsedMs = elapsedMs;
        }

        @Override
        public String kind() {
            return "NativeOperationHung";
        }

        @Override
        public boolean generationScoped() {
            return true;
        }

        @Override
        public long generation() {
            return generation;
        }

        String operation() {
            return operation;
        }

        long elapsedMs() {
            return elapsedMs;
        }
    }

    final class AdapterResetStarted implements DeviceEvent {
        private final long adapterGeneration;

        AdapterResetStarted(long adapterGeneration) {
            this.adapterGeneration = adapterGeneration;
        }

        @Override
        public String kind() {
            return "AdapterResetStarted";
        }

        long adapterGeneration() {
            return adapterGeneration;
        }
    }

    final class AdapterResetCompleted implements DeviceEvent {
        private final long adapterGeneration;
        private final @Nullable String result;

        AdapterResetCompleted(long adapterGeneration, @Nullable String result) {
            this.adapterGeneration = adapterGeneration;
            this.result = result;
        }

        @Override
        public String kind() {
            return "AdapterResetCompleted";
        }

        long adapterGeneration() {
            return adapterGeneration;
        }

        @Nullable
        String result() {
            return result;
        }
    }
}
