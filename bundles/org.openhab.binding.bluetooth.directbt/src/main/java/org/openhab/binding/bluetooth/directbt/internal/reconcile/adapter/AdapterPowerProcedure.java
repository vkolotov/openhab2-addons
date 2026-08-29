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
package org.openhab.binding.bluetooth.directbt.internal.reconcile.adapter;

import java.util.function.Supplier;

import org.direct_bt.BTAdapter;
import org.direct_bt.BTMode;
import org.direct_bt.HCIStatusCode;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.bluetooth.directbt.internal.reconcile.ResetBudget;
import org.slf4j.Logger;

/**
 * Adapter power procedure: observe the adapter handle and issue the native power-up/reset sequence.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
public final class AdapterPowerProcedure {
    /** Observed native adapter truth. */
    public static final class Observed {
        public final boolean present;
        public final boolean valid;
        public final boolean powered;
        public final boolean initialized;
        /**
         * False once the controller reported an unrecoverable hardware fault. Distinct from {@link #valid} and
         * {@link #powered}, which keep reading true in that state because they reflect cached adapter state rather
         * than controller liveness - which is why a wedged controller could previously look perfectly healthy here.
         */
        public final boolean controllerHealthy;

        public Observed(boolean present, boolean valid, boolean powered, boolean initialized) {
            this(present, valid, powered, initialized, true);
        }

        public Observed(boolean present, boolean valid, boolean powered, boolean initialized,
                boolean controllerHealthy) {
            this.present = present;
            this.valid = valid;
            this.powered = powered;
            this.initialized = initialized;
            this.controllerHealthy = controllerHealthy;
        }
    }

    /** Notified when a forced reset completes, so the outcome can be counted without a metrics dependency here. */
    @FunctionalInterface
    public interface ForcedResetListener {
        void onForcedReset(boolean success);
    }

    private final Logger logger;
    private final Supplier<@Nullable BTAdapter> adapterSupplier;
    private final ResetBudget resetBudget;
    private volatile ForcedResetListener forcedResetListener = success -> {
    };

    public AdapterPowerProcedure(Logger logger, Supplier<@Nullable BTAdapter> adapterSupplier,
            ResetBudget resetBudget) {
        this.logger = logger;
        this.adapterSupplier = adapterSupplier;
        this.resetBudget = resetBudget;
    }

    public void setForcedResetListener(ForcedResetListener listener) {
        this.forcedResetListener = listener;
    }

    public Observed observe() {
        BTAdapter a = adapterSupplier.get();
        if (a == null) {
            return new Observed(false, false, false, false);
        }
        boolean valid;
        try {
            valid = a.isValid();
        } catch (RuntimeException e) {
            valid = false;
        }
        boolean controllerHealthy;
        try {
            controllerHealthy = a.isControllerHealthy();
        } catch (RuntimeException e) {
            controllerHealthy = false;
        }
        return new Observed(true, valid, valid && a.isPowered(), valid && a.isInitialized(), controllerHealthy);
    }

    public boolean inSync(boolean wantPowered, Observed o) {
        // A faulted controller is never in sync, however healthy the cached adapter state looks: leaving it "in sync"
        // is what let a wedged dongle sit unrecovered while every device stayed offline.
        return o.present && o.controllerHealthy && o.valid && o.initialized && (!wantPowered || o.powered);
    }

    public void act(boolean wantPowered, Observed o) {
        BTAdapter a = adapterSupplier.get();
        if (a == null) {
            logger.debug("[reconcile:adapter] no adapter handle yet");
            return;
        }
        if (!o.controllerHealthy) {
            // The controller stopped accepting HCI commands, so initialize()/setPowered()/plain reset() are all dead
            // ends - they travel the same channel. A forced reset does not: it drives HCIDEVDOWN/HCIDEVUP through the
            // still-open HCI socket, and the kernel re-runs its device-init path. Budgeted like any other reset.
            if (resetBudget.tryReset("adapter-controller-fault")) {
                HCIStatusCode rc = a.reset(true);
                logger.warn(
                        "[reconcile:adapter] controller hardware fault (code=0x{} count={}): forced reset -> {} (healthy={} powered={})",
                        Integer.toHexString(a.getControllerErrorCode() & 0xFF), a.getControllerErrorCount(), rc,
                        a.isControllerHealthy(), a.isPowered());
                forcedResetListener.onForcedReset(rc == HCIStatusCode.SUCCESS);
                if (rc == HCIStatusCode.SUCCESS && wantPowered && !a.isPowered()) {
                    a.setPowered(true);
                }
            }
            return;
        }
        if (!o.valid) {
            // Adapter invalid but the controller is not known-faulty: nothing this procedure can do; the manager's
            // adapterAdded callback (or a physical re-plug) must supply a fresh handle. Stay out-of-sync so
            // dependents remain paused.
            logger.debug("[reconcile:adapter] no valid adapter handle yet");
            return;
        }
        if (!o.initialized) {
            // Power on as part of initialize (DUAL, wantPowered): initialize(...,false) then a separate power
            // step stalled bring-up on some CSR controllers (see DirectBTBridgeHandler.bringUpAdapter).
            HCIStatusCode rc = a.initialize(BTMode.DUAL, wantPowered);
            logger.debug("[reconcile:adapter] initialize -> {} (powered={} initialized={})", rc, a.isPowered(),
                    a.isInitialized());
            if (rc == HCIStatusCode.SUCCESS && wantPowered && !a.isPowered()) {
                a.setPowered(true);
            }
        } else if (!o.powered) {
            // Initialized but off: try setPowered() first; only reset() if that fails (a blind reset can wedge CSR).
            if (!a.setPowered(true)) {
                // Explicitly unforced: the controller is responsive here, so this is ordinary power-up recovery and
                // must keep reset()'s preconditions and pending-connection drain.
                HCIStatusCode rc = a.reset(false);
                logger.debug("[reconcile:adapter] reset -> {} (powered={} initialized={})", rc, a.isPowered(),
                        a.isInitialized());
                if (rc == HCIStatusCode.SUCCESS && !a.isPowered()) {
                    a.setPowered(true);
                }
            }
        }
    }

    public void escalate(boolean wantPowered, Observed o) {
        BTAdapter a = adapterSupplier.get();
        if (a == null) {
            return;
        }
        // Force past the validity precondition once the delta has persisted: an invalid-looking adapter is exactly
        // the state a wedged controller produces, and refusing to act there is what previously left recovery stuck.
        final boolean force = !o.controllerHealthy || !o.valid;
        if (resetBudget.tryReset("adapter")) {
            HCIStatusCode rc = a.reset(force);
            logger.warn("[reconcile:adapter] escalation reset(force={}) -> {} (healthy={} powered={} initialized={})",
                    force, rc, a.isControllerHealthy(), a.isPowered(), a.isInitialized());
            if (rc == HCIStatusCode.SUCCESS && wantPowered && !a.isPowered()) {
                a.setPowered(true);
            }
        }
    }
}
