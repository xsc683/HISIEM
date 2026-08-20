package com.siem;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.api.common.functions.OpenContext;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;

import java.io.Serializable;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 窗口规则告警治理:收敛重叠滑动窗口对同一规则+keyField 实体产生的重复告警。
 *
 * 窗口本身仍使用事件时间,这里的抑制使用处理时间表达「告警通知不要刷屏」:
 * - 首个窗口命中立即产出一条告警;
 * - 抑制期内后续窗口只更新状态,不创建新告警;
 * - 抑制期结束时用首条告警的内容 ID 输出一次最终累计数,ES sink 以稳定 _id 覆盖更新。
 * 状态由 Flink checkpoint 管理,作业重启后不会因为算子内存丢失而重复建档。
 */
public class WindowAlertSuppressor extends KeyedProcessFunction<String, String, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final long suppressionMillis;
    private transient ValueState<SuppressState> state;

    public WindowAlertSuppressor(Duration suppressionWindow) {
        if (suppressionWindow == null || suppressionWindow.isZero() || suppressionWindow.isNegative()) {
            throw new IllegalArgumentException("窗口告警抑制时长必须 > 0");
        }
        this.suppressionMillis = suppressionWindow.toMillis();
    }

    public static class SuppressState implements Serializable {
        public long suppressUntil;
        public int windowCount;
        public String firstAlertJson;
        public String latestAlertJson;
    }

    /** 默认键与单事件抑制兼容,主要供测试和无显式 keyField 的调用使用。 */
    public static String suppressionKey(String alertJson) throws Exception {
        return suppressionKey(alertJson, null);
    }

    /** 窗口规则优先使用自己的 keyField,否则回退到 source.ip/user.name。 */
    public static String suppressionKey(String alertJson, String preferredEntityField) throws Exception {
        Map<String, Object> alert = MAPPER.readValue(alertJson, Map.class);
        String ruleId = String.valueOf(alert.getOrDefault("alert.rule_id", "unknown"));
        Object entity = preferredEntityField == null ? null : alert.get(preferredEntityField);
        if (entity == null) {
            entity = alert.get("source.ip");
        }
        if (entity == null) {
            entity = alert.get("user.name");
        }
        return ruleId + "|" + (entity == null ? "unknown" : entity);
    }

    @Override
    public void open(OpenContext openContext) {
        state = getRuntimeContext().getState(
                new ValueStateDescriptor<>("window-alert-suppress", SuppressState.class));
    }

    @Override
    public void processElement(String alert, Context ctx, Collector<String> out) throws Exception {
        long now = ctx.timerService().currentProcessingTime();
        SuppressState current = state.value();

        if (current == null || now >= current.suppressUntil) {
            if (current != null) {
                // 定时器可能尚未在本条记录前触发,先完成旧抑制期的最终更新。
                out.collect(mergeLatest(current));
            }
            SuppressState next = new SuppressState();
            next.suppressUntil = now + suppressionMillis;
            next.windowCount = 1;
            next.firstAlertJson = alert;
            next.latestAlertJson = alert;
            state.update(next);
            ctx.timerService().registerProcessingTimeTimer(next.suppressUntil);
            out.collect(withDeduplicatedCount(alert, 1));
            return;
        }

        current.windowCount++;
        current.latestAlertJson = alert;
        state.update(current);
    }

    @Override
    public void onTimer(long timestamp, OnTimerContext ctx, Collector<String> out) throws Exception {
        SuppressState current = state.value();
        if (current != null && current.suppressUntil == timestamp) {
            out.collect(mergeLatest(current));
            state.clear();
        }
    }

    private static String mergeLatest(SuppressState state) throws Exception {
        Map<String, Object> first = MAPPER.readValue(state.firstAlertJson, Map.class);
        Map<String, Object> latest = MAPPER.readValue(state.latestAlertJson, Map.class);

        int firstCount = intValue(first.get("event_count"));
        int latestCount = intValue(latest.get("event_count"));
        first.put("event_count", Math.max(firstCount, latestCount));
        Object related = latest.get("related_events");
        if (related instanceof List<?>) {
            first.put("related_events", related);
        }
        first.put("alert.deduplicated_count", state.windowCount);
        return MAPPER.writeValueAsString(first);
    }

    private static String withDeduplicatedCount(String alert, int count) throws Exception {
        Map<String, Object> value = MAPPER.readValue(alert, Map.class);
        value.put("alert.deduplicated_count", count);
        return MAPPER.writeValueAsString(value);
    }

    private static int intValue(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }
}
