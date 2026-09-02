package com.siem;

import org.apache.flink.api.common.state.KeyedStateStore;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.streaming.api.functions.windowing.ProcessWindowFunction;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.util.Collector;
import org.apache.flink.util.OutputTag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

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

    @Test
    void physicalPlanSigmaMultiplierControlsAnomalyAgainstIdenticalHistory() throws Exception {
        List<Double> rollingHistory = List.of(5.0, 6.0, 4.0, 7.0, 5.0);
        List<Event> currentWindow = List.of(
                event("current-1", "authentication_failure"),
                event("current-2", "authentication_failure"),
                event("current-3", "authentication_failure"),
                event("current-4", "authentication_failure"),
                event("current-5", "authentication_failure"),
                event("current-6", "authentication_failure"),
                event("current-7", "authentication_failure"),
                event("current-8", "authentication_failure"),
                event("current-9", "authentication_failure"),
                event("current-10", "authentication_failure"));

        BaselineAnomalyFunction lowerMultiplier = new BaselineAnomalyFunction(
                10, 5, new FieldEqualsCondition("event.action", "authentication_failure"), 1.0,
                ruleMeta());
        TestValueState<LinkedList<Double>> lowerState = new TestValueState<>(
                new LinkedList<>(rollingHistory));
        injectBaselineState(lowerMultiplier, lowerState);
        List<String> lowerOutput = new ArrayList<>();
        lowerMultiplier.process("server01", context(lowerMultiplier), currentWindow, collector(lowerOutput));

        BaselineAnomalyFunction higherMultiplier = new BaselineAnomalyFunction(
                10, 5, new FieldEqualsCondition("event.action", "authentication_failure"), 5.0,
                ruleMeta());
        TestValueState<LinkedList<Double>> higherState = new TestValueState<>(
                new LinkedList<>(rollingHistory));
        injectBaselineState(higherMultiplier, higherState);
        List<String> higherOutput = new ArrayList<>();
        higherMultiplier.process("server01", context(higherMultiplier), currentWindow, collector(higherOutput));

        assertEquals(1, lowerOutput.size());
        assertTrue(higherOutput.isEmpty());
    }

    @Test
    void physicalPlanConditionControlsBaselineCounting() throws Exception {
        Condition customCondition = fields -> "baseline-target".equals(fields.get("event.category"));
        BaselineAnomalyFunction function = new BaselineAnomalyFunction(
                4, 2, customCondition, 3.0, ruleMeta());
        TestValueState<LinkedList<Double>> state = new TestValueState<>(new LinkedList<>());
        injectBaselineState(function, state);

        function.process("server01", null, List.of(
                event("custom-1", "authentication_success", "baseline-target"),
                event("custom-2", "other", "baseline-target"),
                event("legacy-auth-failure", "authentication_failure", "not-a-target")),
                collector(new ArrayList<>()));

        assertEquals(List.of(2.0), state.value());
    }

    private static Event event(String id, String action) {
        return event(id, action, "default");
    }

    private static Event event(String id, String action, String category) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("event.id", id);
        fields.put("event.action", action);
        fields.put("event.category", category);
        fields.put("host.name", "server01");
        return new Event("raw-" + id, fields, 0L);
    }

    private static RuleMeta ruleMeta() {
        return new RuleMeta("rule-test", "Baseline test", "auth_rate_anomaly", "high",
                "test", 60, List.of("test"), "enabled", "1.0");
    }

    private static Collector<String> collector(List<String> records) {
        return new Collector<>() {
            @Override
            public void collect(String record) {
                records.add(record);
            }

            @Override
            public void close() {
            }
        };
    }

    private static ProcessWindowFunction<Event, String, String, TimeWindow>.Context context(
            BaselineAnomalyFunction function) {
        return function.new Context() {
            @Override
            public TimeWindow window() {
                return new TimeWindow(0L, 1_000L);
            }

            @Override
            public long currentProcessingTime() {
                return 0L;
            }

            @Override
            public long currentWatermark() {
                return 0L;
            }

            @Override
            public KeyedStateStore windowState() {
                return null;
            }

            @Override
            public KeyedStateStore globalState() {
                return null;
            }

            @Override
            public <X> void output(OutputTag<X> outputTag, X value) {
            }
        };
    }

    private static void injectBaselineState(BaselineAnomalyFunction function,
                                            ValueState<LinkedList<Double>> state)
            throws ReflectiveOperationException {
        Field field = BaselineAnomalyFunction.class.getDeclaredField("baselineState");
        field.setAccessible(true);
        field.set(function, state);
    }

    private static final class TestValueState<T> implements ValueState<T> {
        private T value;

        private TestValueState(T value) {
            this.value = value;
        }

        @Override
        public T value() {
            return value;
        }

        @Override
        public void update(T value) {
            this.value = value;
        }

        @Override
        public void clear() {
            value = null;
        }
    }
}
