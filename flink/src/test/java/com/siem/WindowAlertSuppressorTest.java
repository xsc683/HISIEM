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
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class WindowAlertSuppressorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String alert(String timestamp, int eventCount) throws Exception {
        return MAPPER.writeValueAsString(Map.of(
                "@timestamp", timestamp,
                "alert.rule_id", "rule-ssh-brute-force-001",
                "source.ip", "1.2.3.4",
                "event_count", eventCount,
                "related_events", List.of(Map.of("event.id", timestamp))));
    }

    private static List<StreamRecord<String>> output(
            KeyedOneInputStreamOperatorTestHarness<String, String, String> harness) {
        return harness.getOutput().stream()
                .filter(o -> o instanceof StreamRecord)
                .map(o -> (StreamRecord<String>) o)
                .toList();
    }

    private static Map<String, Object> parse(String json) throws Exception {
        return MAPPER.readValue(json, Map.class);
    }

    @Test
    void overlappingWindowsAreMergedIntoOneStableAlert() throws Exception {
        try (KeyedOneInputStreamOperatorTestHarness<String, String, String> harness =
                     new KeyedOneInputStreamOperatorTestHarness<>(
                             new KeyedProcessOperator<>(
                                     new WindowAlertSuppressor(Duration.ofMinutes(5))),
                             WindowAlertSuppressor::suppressionKey,
                             BasicTypeInfo.STRING_TYPE_INFO)) {
            harness.open();
            harness.setProcessingTime(1_000L);

            harness.processElement(new StreamRecord<>(alert("T0", 5)));
            harness.processElement(new StreamRecord<>(alert("T1", 6)));
            harness.processElement(new StreamRecord<>(alert("T2", 7)));

            List<StreamRecord<String>> immediate = output(harness);
            assertEquals(1, immediate.size(), "重叠窗口在抑制期内只能立即产出一条");
            assertEquals(1, parse(immediate.get(0).getValue()).get("alert.deduplicated_count"));

            harness.setProcessingTime(6 * 60_000L);
            List<StreamRecord<String>> finalOutput = output(harness);
            assertEquals(2, finalOutput.size(), "抑制期结束应输出一次最终更新");
            Map<String, Object> merged = parse(finalOutput.get(1).getValue());
            assertEquals("T0", merged.get("@timestamp"), "最终更新必须保留首条告警时间以稳定 ES _id");
            assertEquals(7, merged.get("event_count"));
            assertEquals(3, merged.get("alert.deduplicated_count"));

            harness.setProcessingTime(6 * 60_000L + 1_000L);
            harness.processElement(new StreamRecord<>(alert("T3", 5)));
            List<StreamRecord<String>> next = output(harness);
            assertEquals(3, next.size(), "抑制期结束后的新窗口应产生新告警");
            assertNotEquals("T0", parse(next.get(2).getValue()).get("@timestamp"));
        }
    }

    @Test
    void windowRuleKeyFieldSeparatesEntitiesBeyondSourceIp() throws Exception {
        String hostAlert = MAPPER.writeValueAsString(Map.of(
                "alert.rule_id", "r-host",
                "host.name", "server-a",
                "source.ip", "1.2.3.4"));

        assertEquals("r-host|server-a",
                WindowAlertSuppressor.suppressionKey(hostAlert, "host.name"));
    }
}
