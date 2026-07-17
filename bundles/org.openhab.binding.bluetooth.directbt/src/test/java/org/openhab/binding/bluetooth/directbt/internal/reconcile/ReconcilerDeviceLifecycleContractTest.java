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

/**
 * Runs the implementation-neutral {@link DeviceLifecycleContract} against the current
 * actor-backed {@link DeviceReconciler}.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
class ReconcilerDeviceLifecycleContractTest extends DeviceLifecycleContract {

    @Override
    protected DeviceLifecycleFixture newFixture() {
        return new ReconcilerDeviceLifecycleFixture();
    }
}
