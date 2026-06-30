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
package org.openhab.binding.bluetooth.directbt.internal;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;

import org.direct_bt.BTFactory;
import org.direct_bt.BTManager;
import org.direct_bt.osgi.DirectBTNativeLibraryProvider;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.bluetooth.util.RetryFuture;
import org.openhab.core.common.ThreadPoolManager;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the lifecycle of the Direct-BT {@link BTManager} singleton, mirroring the BlueZ
 * {@code DeviceManagerFactory}.
 * <p>
 * This is a long-lived OSGi Declarative-Services component: it acquires the {@link BTManager} exactly once
 * (asynchronously, retried) on {@link #initialize() activation} and hands it out via {@link #getManager()}.
 * Keeping the manager OUT of the bridge handler is what makes the binding bundle hot-swappable: the native
 * JNI library and the {@code BTManager} process-singleton are owned by the Direct-BT lib bundle's classloader
 * (the {@code System.load} happens in {@code org.jau}/{@code org.direct_bt} classes), so they survive a
 * binding-bundle refresh; this component then simply re-acquires the still-live singleton on re-activation
 * ({@code BTFactory.getDirectBTManager()} is idempotent). Acquisition can fail transiently (missing
 * CAP_NET_ADMIN/CAP_NET_RAW, bluetoothd still owning the adapter, or no adapter present yet), so callers must
 * tolerate a {@code null} from {@link #getManager()} and retry.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
@Component(service = DirectBTManagerFactory.class)
public class DirectBTManagerFactory {

    private final Logger logger = LoggerFactory.getLogger(DirectBTManagerFactory.class);
    private final ScheduledExecutorService scheduler = ThreadPoolManager.getScheduledPool("bluetooth");
    private final DirectBTNativeLibraryProvider nativeLibraryProvider;

    private @Nullable CompletableFuture<@Nullable BTManager> managerFuture;

    @Activate
    public DirectBTManagerFactory(@Reference DirectBTNativeLibraryProvider nativeLibraryProvider) {
        this.nativeLibraryProvider = nativeLibraryProvider;
        initialize();
    }

    /**
     * @return the shared {@link BTManager}, or {@code null} if it has not been acquired yet (acquisition is
     *         asynchronous and may be retrying). Callers should retry on {@code null}.
     */
    public @Nullable BTManager getManager() {
        CompletableFuture<@Nullable BTManager> future = managerFuture;
        if (future != null) {
            return future.getNow(null);
        }
        return null;
    }

    private void initialize() {
        logger.debug("initializing DirectBTManagerFactory; Direct-BT natives ready in {}",
                nativeLibraryProvider.getLibraryDirectory());
        this.managerFuture = RetryFuture.callWithRetry(() -> {
            try {
                // The native libraries are extracted + System.load'ed inside the Direct-BT lib bundle (its
                // Bundle-Activator), so this call only acquires the (idempotent) manager singleton. After a
                // binding-bundle refresh the native singleton is still live and this returns the same instance.
                return BTFactory.getDirectBTManager();
            } catch (UnsupportedOperationException e) {
                // Direct-BT not supported on this platform/arch.
                logger.debug("Direct-BT not supported on this platform: {}", e.getMessage());
                return null;
            } catch (Exception e) {
                // Most commonly: missing CAP_NET_ADMIN/CAP_NET_RAW, bluetoothd owning the adapter, or the
                // native lib not yet loaded by the lib bundle. Surface as not-ready; the caller keeps retrying.
                logger.debug("Failed to acquire Direct-BT manager (caps/bluetoothd/native?): {}", e.getMessage());
                return null;
            }
        }, scheduler);
    }

    @Deactivate
    public void dispose() {
        CompletableFuture<@Nullable BTManager> future = this.managerFuture;
        if (future != null) {
            future.cancel(true);
        }
        this.managerFuture = null;
        // Intentionally do NOT shut down the native BTManager: it is a process-wide singleton with no clean
        // re-create, owned by the lib bundle. Leaving it alive lets a binding-bundle refresh re-acquire it.
    }
}
