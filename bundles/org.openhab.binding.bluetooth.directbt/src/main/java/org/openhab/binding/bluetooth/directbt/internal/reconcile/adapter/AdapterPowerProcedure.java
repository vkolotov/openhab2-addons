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

        public Observed(boolean present, boolean valid, boolean powered, boolean initialized) {
            this.present = present;
            this.valid = valid;
            this.powered = powered;
            this.initialized = initialized;
        }
    }

    private final Logger logger;
    private final Supplier<@Nullable BTAdapter> adapterSupplier;
    private final ResetBudget resetBudget;

    public AdapterPowerProcedure(Logger logger, Supplier<@Nullable BTAdapter> adapterSupplier,
            ResetBudget resetBudget) {
        this.logger = logger;
        this.adapterSupplier = adapterSupplier;
        this.resetBudget = resetBudget;
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
        return new Observed(true, valid, valid && a.isPowered(), valid && a.isInitialized());
    }

    public boolean inSync(boolean wantPowered, Observed o) {
        return o.present && o.valid && o.initialized && (!wantPowered || o.powered);
    }

    public void act(boolean wantPowered, Observed o) {
        BTAdapter a = adapterSupplier.get();
        if (a == null || !o.valid) {
            // Adapter absent/invalid: nothing this procedure can do; the manager's adapterAdded callback (or a
            // physical re-plug) must supply a fresh handle. Stay out-of-sync so dependents remain paused.
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
                HCIStatusCode rc = a.reset();
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
        if (a == null || !o.valid) {
            return;
        }
        if (resetBudget.tryReset("adapter")) {
            HCIStatusCode rc = a.reset();
            logger.warn("[reconcile:adapter] escalation reset -> {} (powered={} initialized={})", rc, a.isPowered(),
                    a.isInitialized());
            if (rc == HCIStatusCode.SUCCESS && !a.isPowered()) {
                a.setPowered(true);
            }
        }
    }
}
