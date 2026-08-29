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

import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs submitted tasks strictly one at a time, in submission order, on a delegate executor. Used per device for
 * openHAB notification fanout: the delegate may be a multi-threaded pool, but characteristic updates for one
 * device must keep the order the native reader delivered them in, or listeners can observe values regressing
 * (fatal for value-match confirmation patterns). A failing task is logged and does not stop the queue.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
final class SerialExecutor implements Executor {

    private final Logger logger = LoggerFactory.getLogger(SerialExecutor.class);
    private final ArrayDeque<Runnable> queue = new ArrayDeque<>();
    private final Executor delegate;
    private boolean draining; // guarded by queue

    SerialExecutor(Executor delegate) {
        this.delegate = delegate;
    }

    @Override
    public void execute(Runnable task) {
        synchronized (queue) {
            queue.add(task);
            if (draining) {
                return; // the active drain will pick it up
            }
            draining = true;
        }
        try {
            delegate.execute(this::drain);
        } catch (RejectedExecutionException e) {
            // Delegate shutting down: drop the queue rather than leak it (nothing will drain anymore).
            synchronized (queue) {
                queue.clear();
                draining = false;
            }
        }
    }

    private void drain() {
        while (true) {
            Runnable task;
            synchronized (queue) {
                if (queue.isEmpty()) {
                    draining = false;
                    return;
                }
                task = queue.remove();
            }
            try {
                task.run();
            } catch (RuntimeException e) {
                logger.warn("Serialized task failed", e);
            }
        }
    }
}
