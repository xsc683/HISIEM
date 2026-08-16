package com.siem;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 基线异常判定(μ+3σ)的正负夹具。
 */
public class BaselineAnomalyTest {

    @Test
    void insufficientBaselineIsNotAnomaly() {
        // 负样本:基线不足 / 空基线,即使当前值很大也不判异常
        assertFalse(BaselineAnomalyFunction.isAnomaly(List.of(1.0, 2.0), 100, 3));
        assertFalse(BaselineAnomalyFunction.isAnomaly(List.of(), 100, 1));
    }

    @Test
    void burstAboveMeanPlus3SigmaIsAnomaly() {
        // 正样本:零方差基线(全 1),当前 10 >> μ+3σ=1 → 异常
        assertTrue(BaselineAnomalyFunction.isAnomaly(List.of(1.0, 1.0, 1.0, 1.0, 1.0), 10, 3));
        // 正样本:有波动的基线,当前值远超 → 异常
        List<Double> baseline = List.of(5.0, 6.0, 4.0, 7.0, 5.0);
        assertTrue(BaselineAnomalyFunction.isAnomaly(baseline, 30, 3));
    }

    @Test
    void normalLevelIsNotAnomaly() {
        // 负样本:正常水平 / 小幅波动不判异常
        List<Double> baseline = List.of(5.0, 6.0, 4.0, 7.0, 5.0);
        assertFalse(BaselineAnomalyFunction.isAnomaly(baseline, 6, 3));
        assertFalse(BaselineAnomalyFunction.isAnomaly(baseline, 8, 3));
    }

    @Test
    void meanSigmaComputed() {
        double[] ms = BaselineAnomalyFunction.meanSigma(List.of(1.0, 1.0, 1.0, 1.0, 1.0));
        assertEquals(1.0, ms[0], 1e-9);
        assertEquals(0.0, ms[1], 1e-9);
    }
}
