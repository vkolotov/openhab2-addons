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

import static org.junit.jupiter.api.Assertions.*;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;

/**
 * Tests the I-part limits supplied per calculation, as read from the limit Items.
 *
 * @author Vlad Kolotoff - Initial contribution
 */
@NonNullByDefault
class PIDControllerIntegralLimitsTest {
    private static final int LOOP_TIME_MS = 1000;
    private static final double KD_TIME_CONSTANT_SEC = 10;

    private PIDController createController(double ki, double configuredMin, double configuredMax) {
        return new PIDController(1, ki, 0, KD_TIME_CONSTANT_SEC, configuredMin, configuredMax, 0, false, Double.NaN,
                Double.NaN, Double.NaN);
    }

    private double run(PIDController controller, double error, int invocations, double min, double max) {
        double integralPart = 0;
        for (int i = 0; i < invocations; i++) {
            integralPart = controller.calculate(0, error, LOOP_TIME_MS, LOOP_TIME_MS, false, min, max)
                    .getIntegralPart();
        }
        return integralPart;
    }

    @Test
    void perCalculationLimitsClampBothSides() {
        PIDController controller = createController(1, Double.NaN, Double.NaN);

        assertEquals(3, run(controller, 1, 20, -2, 3), 1e-9);
        assertEquals(-2, run(controller, -1, 40, -2, 3), 1e-9);
    }

    @Test
    void perCalculationLimitsOverrideTheConfiguredOnes() {
        PIDController controller = createController(1, -10, 10);

        assertEquals(3, run(controller, 1, 20, -10, 3), 1e-9);
    }

    @Test
    void configuredLimitsStillApplyWithoutOverride() {
        PIDController controller = createController(1, -10, 4);
        double integralPart = 0;
        for (int i = 0; i < 20; i++) {
            integralPart = controller.calculate(0, 1, LOOP_TIME_MS, LOOP_TIME_MS, false).getIntegralPart();
        }

        assertEquals(4, integralPart, 1e-9);
    }

    @Test
    void limitsAreInOutputUnitsWhateverTheGain() {
        PIDController controller = createController(0.25, Double.NaN, Double.NaN);

        assertEquals(3, run(controller, 1, 100, -2, 3), 1e-9);
    }

    @Test
    void aTighterLimitClampsOnTheNextCalculation() {
        PIDController controller = createController(1, Double.NaN, Double.NaN);
        assertEquals(8, run(controller, 1, 8, -10, 10), 1e-9);

        // the I-part is clamped to the new limit at once, then the error keeps it there
        assertEquals(2, run(controller, 1, 1, -10, 2), 1e-9);
        // and once the limit is relaxed it grows again from the clamped value, not from where it was
        assertEquals(3, run(controller, 1, 1, -10, 10), 1e-9);
    }

    @Test
    void noLimitWhenBothAreNaN() {
        PIDController controller = createController(1, Double.NaN, Double.NaN);

        assertEquals(50, run(controller, 1, 50, Double.NaN, Double.NaN), 1e-9);
    }

    @Test
    void itemValuesOverrideTheConfiguredLimits() {
        IntegralLimits limits = IntegralLimits.resolve(-100, 100, -32, 14);

        assertEquals(-32, limits.min());
        assertEquals(14, limits.max());
        assertFalse(limits.crossed());
    }

    @Test
    void anItemWithoutAValueFallsBackToItsConfiguredLimit() {
        IntegralLimits limits = IntegralLimits.resolve(-100, 100, Double.NaN, 14);

        assertEquals(-100, limits.min());
        assertEquals(14, limits.max());
    }

    @Test
    void noItemAndNoConfigurationMeansNoLimit() {
        IntegralLimits limits = IntegralLimits.resolve(Double.NaN, Double.NaN, Double.NaN, Double.NaN);

        assertTrue(Double.isNaN(limits.min()));
        assertTrue(Double.isNaN(limits.max()));
    }

    @Test
    void crossedLimitsFallBackToTheConfiguredPair() {
        IntegralLimits limits = IntegralLimits.resolve(-100, 100, 20, 10);

        assertTrue(limits.crossed());
        assertEquals(-100, limits.min());
        assertEquals(100, limits.max());
    }

    @Test
    void anItemCrossingTheOtherSidesConfiguredLimitIsAlsoRejected() {
        // only the lower limit has an Item, and its value lies above the configured upper limit
        IntegralLimits limits = IntegralLimits.resolve(-100, 10, 20, Double.NaN);

        assertTrue(limits.crossed());
        assertEquals(-100, limits.min());
        assertEquals(10, limits.max());
    }
}
