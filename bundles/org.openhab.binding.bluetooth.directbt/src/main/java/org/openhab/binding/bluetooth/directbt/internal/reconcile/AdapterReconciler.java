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

import java.util.function.Supplier;

import org.direct_bt.BTAdapter;
import org.direct_bt.BTMode;
import org.direct_bt.HCIStatusCode;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.slf4j.Logger;

/**
 * Reconciles the adapter to a powered, present, valid state (design table rows 1-2). This is the root of the
 * dependency DAG: while it is not in-sync, the discovery and device reconcilers are paused.
 * <p>
 * act = power the adapter up (initialize if never initialized, else reset+setPowered). escalate (delta
 * persisted) = full {@link BTAdapter#reset()}. All resets go through the shared {@link ResetBudget} so the
 * adapter and discovery reconcilers cannot both reset within the cooldown (which previously un-powered the CSR).
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
public class AdapterReconciler extends Reconciler<Boolean, AdapterReconciler.Observed> {

    /** Observed native adapter truth. */
    public static final class Observed {
        public final boolean present;
        public final boolean valid;
        public final boolean powered;
        public final boolean initialized;

        Observed(boolean present, boolean valid, boolean powered, boolean initialized) {
            this.present = present;
            this.valid = valid;
            this.powered = powered;
            this.initialized = initialized;
        }
    }

    private static final long ESCALATE_AFTER_MS = 6000;

    private final Supplier<@Nullable BTAdapter> adapterSupplier;
    private final ResetBudget resetBudget;

    public AdapterReconciler(Logger logger, Supplier<@Nullable BTAdapter> adapterSupplier, ResetBudget resetBudget) {
        super("adapter", logger, Boolean.TRUE); // desired = powered
        this.adapterSupplier = adapterSupplier;
        this.resetBudget = resetBudget;
    }

    @Override
    protected Observed observe() {
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

    @Override
    protected boolean inSync(Boolean wantPowered, Observed o) {
        return o.present && o.valid && (!wantPowered || o.powered);
    }

    @Override
    protected void act(Boolean wantPowered, Observed o) {
        BTAdapter a = adapterSupplier.get();
        if (a == null || !o.valid) {
            // Adapter absent/invalid: nothing this reconciler can do; the manager's adapterAdded callback (or a
            // physical re-plug) must supply a fresh handle. Stay out-of-sync so dependents remain paused.
            logger.debug("[reconcile:adapter] no valid adapter handle yet");
            return;
        }
        if (!o.powered) {
            HCIStatusCode rc;
            if (!o.initialized) {
                rc = a.initialize(BTMode.DUAL, true);
                logger.debug("[reconcile:adapter] initialize -> {} (powered={})", rc, a.isPowered());
            } else {
                rc = a.reset();
                logger.debug("[reconcile:adapter] reset -> {} (powered={})", rc, a.isPowered());
                if (rc == HCIStatusCode.SUCCESS && !a.isPowered()) {
                    a.setPowered(true);
                }
            }
        }
    }

    @Override
    protected long escalateAfterMillis() {
        return ESCALATE_AFTER_MS;
    }

    @Override
    protected void escalate(Boolean wantPowered, Observed o) {
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
