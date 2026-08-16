package com.siem;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.api.common.functions.OpenContext;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;

import java.io.Serializable;
import java.time.Duration;
import java.util.Map;

/**
 * 单事件规则告警抑制(Phase 3.1-F6):同一「规则 + 实体(source.ip/user.name)」在抑制窗口内
 * 只产出一条告警,后续命中仅累加 alert.deduplicated_count,不新建告警。
 *
 * 实现:keyBy(rule_id + 实体) + 处理时间(墙钟)窗口。用处理时间而非事件时间,
 * 语义是"1 小时内同一实体同一规则不要刷屏",与事件时间窗口(暴力破解)区分。
 *
 * 行为:
 * - 首个命中:立即产出告警(deduplicated_count=1),登记窗口结束定时器,缓存首个告警 JSON;
 * - 窗口内后续命中:仅累加状态计数,不产出;
 * - 窗口结束(onTimer):产出带最终 count 的告警(首个告警 JSON 的 @timestamp 不变 → _id 稳定,
 *   ES upsert 覆盖更新),随后清空状态。
 */
public class AlertSuppressor extends KeyedProcessFunction<String, String, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final long windowMillis;
    private transient ValueState<SuppressState> state;

    public AlertSuppressor(Duration window) {
        this.windowMillis = window.toMillis();
    }

    /** 抑制状态:窗口起始(处理时间)+ 累计命中数 + 首个告警 JSON。 */
    public static class SuppressState implements Serializable {
        public long windowStart;
        public int count;
        public String firstAlertJson;
    }

    /** 抑制键 = rule_id + 实体(source.ip 优先,其次 user.name)。供 keyBy 使用。 */
    public static String suppressionKey(String alertJson) throws Exception {
        Map<String, Object> alert = MAPPER.readValue(alertJson, Map.class);
        String ruleId = String.valueOf(alert.getOrDefault("alert.rule_id", "unknown"));
        Object ip = alert.get("source.ip");
        Object user = alert.get("user.name");
        String entity = ip != null ? String.valueOf(ip)
                : (user != null ? String.valueOf(user) : "unknown");
        return ruleId + "|" + entity;
    }

    @Override
    public void open(OpenContext openContext) {
        ValueStateDescriptor<SuppressState> desc =
                new ValueStateDescriptor<>("suppress", SuppressState.class);
        state = getRuntimeContext().getState(desc);
    }

    @Override
    public void processElement(String alert, Context ctx, Collector<String> out) throws Exception {
        long now = ctx.timerService().currentProcessingTime();
        long windowStart = (now / windowMillis) * windowMillis;

        SuppressState cur = state.value();
        if (cur == null || cur.windowStart != windowStart) {
            // 新窗口:首个命中立即产出告警,并登记窗口结束定时器
            cur = new SuppressState();
            cur.windowStart = windowStart;
            cur.count = 1;
            cur.firstAlertJson = alert;   // 首个告警(含首事件 @timestamp,保证 _id 稳定)
            state.update(cur);
            ctx.timerService().registerProcessingTimeTimer(windowStart + windowMillis);
            out.collect(updateCount(alert, cur.count));
        } else {
            // 窗口内后续命中:仅累加计数,不产出(窗口结束定时器会产出最终 count)
            cur.count++;
            state.update(cur);
        }
    }

    @Override
    public void onTimer(long timestamp, OnTimerContext ctx, Collector<String> out) throws Exception {
        SuppressState cur = state.value();
        if (cur != null && cur.firstAlertJson != null) {
            // 产出最终 count(同一 _id,ES upsert 更新);随后清状态
            out.collect(updateCount(cur.firstAlertJson, cur.count));
        }
        state.clear();
    }

    private static String updateCount(String alertJson, int count) throws Exception {
        Map<String, Object> alert = MAPPER.readValue(alertJson, Map.class);
        alert.put("alert.deduplicated_count", count);
        return MAPPER.writeValueAsString(alert);
    }
}
