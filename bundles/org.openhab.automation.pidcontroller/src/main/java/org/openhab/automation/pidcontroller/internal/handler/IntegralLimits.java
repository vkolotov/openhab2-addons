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
package org.openhab.automation.pidcontroller.internal.handler;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * The I-part limits in force for one calculation, in output units. {@link Double#NaN} means no limit on that side.
 *
 * <p>
 * Each limit comes from its limit Item when that Item holds a number, and from the configured value otherwise, so an
 * Item that is missing, NULL or UNDEF degrades to the static configuration rather than removing the limit.
 *
 * <p>
 * Limits that cross (lower above upper) are rejected as a pair and both configured values are used instead. Clamping
 * with crossed limits would pin the I-part to one of them regardless of the error, and silently preferring either side
 * would hide the misconfiguration.
 *
 * @author Vlad Kolotoff - Initial contribution
 */
@NonNullByDefault
record IntegralLimits(double min, double max, boolean crossed) {

    /**
     * @param configuredMin the {@code integralMinValue} configuration, or NaN when not set
     * @param configuredMax the {@code integralMaxValue} configuration, or NaN when not set
     * @param itemMin the value of the lower limit Item, or NaN when there is no usable value
     * @param itemMax the value of the upper limit Item, or NaN when there is no usable value
     */
    static IntegralLimits resolve(double configuredMin, double configuredMax, double itemMin, double itemMax) {
        double min = Double.isFinite(itemMin) ? itemMin : configuredMin;
        double max = Double.isFinite(itemMax) ? itemMax : configuredMax;
        if (Double.isFinite(min) && Double.isFinite(max) && min > max) {
            return new IntegralLimits(configuredMin, configuredMax, true);
        }
        return new IntegralLimits(min, max, false);
    }
}
