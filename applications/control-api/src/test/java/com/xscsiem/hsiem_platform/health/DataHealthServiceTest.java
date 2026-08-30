package com.xscsiem.hsiem_platform.health;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataHealthServiceTest {

    @Test
    void calculateMetrics_usesSuccessfulPlusFailedAsDenominator() {
        DataHealthService.HealthMetrics metrics = DataHealthService.calculateMetrics(
                90, 95, 10, 5, 20);

        assertEquals(100, metrics.total1h());
        assertEquals(0.10, metrics.failRate(), 0.0001);
        assertTrue(metrics.high());
        assertTrue(metrics.anomalous());
    }

    @Test
    void calculateMetrics_lowSampleDoesNotCreateHighRateAlert() {
        DataHealthService.HealthMetrics metrics = DataHealthService.calculateMetrics(
                1, 0, 1, 0, 20);

        assertEquals(0.50, metrics.failRate(), 0.0001);
        assertFalse(metrics.high());
        assertFalse(metrics.anomalous());
    }

    @Test
    void calculateMetrics_supportsFailureOnlySource() {
        DataHealthService.HealthMetrics metrics = DataHealthService.calculateMetrics(
                0, 0, 20, 0, 20);

        assertEquals(20, metrics.total1h());
        assertEquals(1.0, metrics.failRate(), 0.0001);
        assertTrue(metrics.high());
    }
}
