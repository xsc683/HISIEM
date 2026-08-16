package com.siem;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.api.common.typeinfo.BasicTypeInfo;
import org.apache.flink.streaming.api.operators.KeyedProcessOperator;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SuppressionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String alert(String ruleId, String ip, String user, String ts) {
        return "{\"alert.rule_id\":\"" + ruleId + "\","
                + "\"source.ip\":\"" + ip + "\","
                + "\"user.name\":\"" + user + "\","
                + "\"@timestamp\":\"" + ts + "\"}";
    }

    private static List<StreamRecord<String>> output(KeyedOneInputStreamOperatorTestHarness<String, String, String> h) {
        // getOutput() 返回 ConcurrentLinkedQueue,过滤出 StreamRecord(排除 Watermark 等)
        return h.getOutput().stream()
                .filter(o -> o instanceof StreamRecord)
                .map(o -> (StreamRecord<String>) o)
                .collect(java.util.stream.Collectors.toList());
    }

    private static Map<String, Object> parse(String json) throws Exception {
        return MAPPER.readValue(json, Map.class);
    }

    @Test
    void sameEntitySameRuleWithinWindowEmitsOneAlertAndAccumulatesCount() throws Exception {
        try (KeyedOneInputStreamOperatorTestHarness<String, String, String> harness =
                     new KeyedOneInputStreamOperatorTestHarness<>(
                             new KeyedProcessOperator<>(new AlertSuppressor(Duration.ofMinutes(60))),
                             AlertSuppressor::suppressionKey,
                             BasicTypeInfo.STRING_TYPE_INFO)) {
            harness.open();
            harness.setProcessingTime(1_000L);

            // 同实体(1.2.3.4)同规则 3 次命中:窗口内只产出 1 条(count=1,立即)
            harness.processElement(new StreamRecord<>(alert("r1", "1.2.3.4", "alice", "T0")));
            harness.processElement(new StreamRecord<>(alert("r1", "1.2.3.4", "alice", "T1")));
            harness.processElement(new StreamRecord<>(alert("r1", "1.2.3.4", "alice", "T2")));

            List<StreamRecord<String>> out = output(harness);
            assertEquals(1, out.size(), "窗口内只应产出 1 条告警");
            assertEquals(1, (int) parse(out.get(0).getValue()).get("alert.deduplicated_count"),
                    "首个命中立即产出 count=1");

            // 推进到窗口结束(定时器):产出最终 count=3
            harness.setProcessingTime(61 * 60_000L);
            List<StreamRecord<String>> out2 = output(harness);
            assertEquals(2, out2.size(), "窗口结束时产出最终 count 告警");
            assertEquals(3, (int) parse(out2.get(1).getValue()).get("alert.deduplicated_count"),
                    "窗口结束告警携带累计 count");
            // 最终告警的 @timestamp 保持首事件时间(_id 稳定,ES upsert)
            assertEquals("T0", parse(out2.get(1).getValue()).get("@timestamp"));
        }
    }

    @Test
    void differentEntityGetsSeparateAlert() throws Exception {
        try (KeyedOneInputStreamOperatorTestHarness<String, String, String> harness =
                     new KeyedOneInputStreamOperatorTestHarness<>(
                             new KeyedProcessOperator<>(new AlertSuppressor(Duration.ofMinutes(60))),
                             AlertSuppressor::suppressionKey,
                             BasicTypeInfo.STRING_TYPE_INFO)) {
            harness.open();
            harness.setProcessingTime(1_000L);

            harness.processElement(new StreamRecord<>(alert("r1", "1.2.3.4", "alice", "T0")));
            harness.processElement(new StreamRecord<>(alert("r1", "5.6.7.8", "bob", "T1")));

            // 不同实体互不抑制 → 各产出一条
            assertEquals(2, output(harness).size());
        }
    }

    @Test
    void nextWindowProducesNewAlert() throws Exception {
        try (KeyedOneInputStreamOperatorTestHarness<String, String, String> harness =
                     new KeyedOneInputStreamOperatorTestHarness<>(
                             new KeyedProcessOperator<>(new AlertSuppressor(Duration.ofMinutes(60))),
                             AlertSuppressor::suppressionKey,
                             BasicTypeInfo.STRING_TYPE_INFO)) {
            harness.open();
            harness.setProcessingTime(1_000L);
            harness.processElement(new StreamRecord<>(alert("r1", "1.2.3.4", "alice", "T0")));

            // 跨入下一个窗口(61 分钟后):同一实体同一规则应产出新的告警
            harness.setProcessingTime(61 * 60_000L);   // 触发上一窗口定时器
            harness.setProcessingTime(61 * 60_000L + 1_000L);  // 新窗口内
            harness.processElement(new StreamRecord<>(alert("r1", "1.2.3.4", "alice", "T2")));

            List<StreamRecord<String>> out = output(harness);
            // 旧窗口最终告警 + 新窗口首个告警
            assertTrue(out.size() >= 2, "跨窗口应产生新告警");
            assertEquals("T2", parse(out.get(out.size() - 1).getValue()).get("@timestamp"),
                    "新窗口告警使用新事件时间");
        }
    }
}
