package com.siem;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.RestartStrategyOptions;
import org.apache.flink.connector.elasticsearch.sink.Elasticsearch8AsyncSinkBuilder;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.streaming.api.CheckpointingMode;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.windowing.assigners.TumblingEventTimeWindows;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;

import co.elastic.clients.elasticsearch.core.bulk.IndexOperation;
import co.elastic.clients.json.JsonData;
import org.apache.http.HttpHost;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.List;
import java.util.Map;


public class DetectionJob {

    private static final ObjectMapper ALERT_MAPPER = new ObjectMapper();

    public static void main(String[] args) throws Exception {

        // checkpoint/savepoint 落到 Docker 挂载的持久卷(/opt/flink/checkpoints),
        // 容器重建不丢失,保证恢复能力(Phase 3.0-F1)。
        Configuration conf = new Configuration();
        conf.setString("state.checkpoints.dir", "file:///opt/flink/checkpoints");
        conf.setString("execution.checkpointing.savepoint-dir", "file:///opt/flink/checkpoints");
        // 重启策略(Phase 3.0-F3):exponential-delay,Flink 2.x 通过 Configuration 选项配置
        // (旧版 RestartStrategies 工厂已移除)。
        conf.set(RestartStrategyOptions.RESTART_STRATEGY, "exponential-delay");
        conf.set(RestartStrategyOptions.RESTART_STRATEGY_EXPONENTIAL_DELAY_INITIAL_BACKOFF, Duration.ofSeconds(5));
        conf.set(RestartStrategyOptions.RESTART_STRATEGY_EXPONENTIAL_DELAY_MAX_BACKOFF, Duration.ofMinutes(2));
        conf.set(RestartStrategyOptions.RESTART_STRATEGY_EXPONENTIAL_DELAY_BACKOFF_MULTIPLIER, 1.5);
        conf.set(RestartStrategyOptions.RESTART_STRATEGY_EXPONENTIAL_DELAY_JITTER_FACTOR, 0.1);
        conf.set(RestartStrategyOptions.RESTART_STRATEGY_EXPONENTIAL_DELAY_ATTEMPTS, 10);

        StreamExecutionEnvironment env =
                StreamExecutionEnvironment.getExecutionEnvironment(conf);

        // 并行度(Phase 3.0-K1):与 2-slot taskmanager 匹配,
        // KafkaSource 以 2 个并行消费者分摊 3 分区的 siem-events。
        env.setParallelism(2);

        // 可靠性基线(Phase 3.0-F3):
        // 1) EXACTLY_ONCE + 明确 timeout/min-pause/max-concurrent,避免 checkpoint 风暴;
        // 2) tolerable-failed-checkpoints=3,单次失败不杀 job。
        env.enableCheckpointing(60_000, CheckpointingMode.EXACTLY_ONCE);
        env.getCheckpointConfig().setCheckpointTimeout(5 * 60_000);
        env.getCheckpointConfig().setMinPauseBetweenCheckpoints(30_000);
        env.getCheckpointConfig().setMaxConcurrentCheckpoints(1);
        env.getCheckpointConfig().setTolerableCheckpointFailureNumber(3);

        KafkaSource<String> source =
                KafkaSource.<String>builder()
                        .setBootstrapServers("kafka:9092")
                        .setTopics("siem-events")
                        .setGroupId("siem-detection")
                        .setStartingOffsets(
                                // 从已提交的 group offset 恢复;首次运行(无已提交 offset)回退到 earliest
                                OffsetsInitializer.committedOffsets(OffsetResetStrategy.EARLIEST)
                        )
                        .setValueOnlyDeserializer(
                                new SimpleStringSchema()
                        )
                        .build();


        DataStream<String> events =
                env.fromSource(
                                source,
                                WatermarkStrategy.noWatermarks(),
                                "siem-events-source"
                        )
                        .uid("kafka-source");

        // 解析事件:扁平字段 + 事件时间戳(供事件时间窗口使用)
        DataStream<Event> parsed = events
                .map(EventParser::parseEvent)
                .uid("event-parser");


        /*
         * 1) 单事件规则:逐规则匹配(RuleRegistry)→ 生成符合 Alert Schema 的告警 JSON。
         */
        DataStream<String> singleAlerts =
                parsed
                        .flatMap(new DetectionFunction(new RuleRegistry()))
                        .uid("single-event-detection")
                        // 告警抑制(Phase 3.1-F6):同一规则+实体在抑制窗口内只发一条,
                        // 后续命中累加 alert.deduplicated_count。
                        .keyBy(AlertSuppressor::suppressionKey)
                        .process(new AlertSuppressor(Duration.ofMinutes(60)))
                        .uid("alert-suppression");


        /*
         * 2) 时间窗口规则:SSH 暴力破解
         *    同一源 IP 5 分钟内 authentication_failure >= 5 次 → critical 告警。
         *    事件时间窗口 + 有界乱序 watermark。
         */
        WindowRule bruteForce = new WindowRule(
                "rule-ssh-brute-force-001",
                "SSH 暴力破解",
                "ssh_brute_force",
                "critical",
                "同一源 IP 短时间多次认证失败,疑似暴力破解",
                "source.ip",
                new FieldEqualsCondition("event.action", "authentication_failure"),
                5, 5,
                73,
                List.of("attack.t1110.001"),
                "experimental");

        DataStream<String> windowAlerts =
                parsed
                        .assignTimestampsAndWatermarks(
                                WatermarkStrategy.<Event>forBoundedOutOfOrderness(Duration.ofSeconds(10))
                                        .withTimestampAssigner((e, ts) -> e.getTimestampMillis())
                                        // 日志暂停(SSH 突发写入)时推进 watermark,窗口仍能按时关闭(Phase 3.1-F5)
                                        .withIdleness(Duration.ofSeconds(60))
                        )
                        .uid("window-watermark")
                        .keyBy(e -> String.valueOf(
                                e.getFields().getOrDefault(bruteForce.getKeyField(), "unknown")))
                        .window(TumblingEventTimeWindows.of(
                                Duration.ofMinutes(bruteForce.getWindowMinutes())))
                        .process(new WindowRuleFunction(bruteForce))
                        .uid("window-rule");


        DataStream<String> alerts = singleAlerts.union(windowAlerts);

        alerts.print();
        alerts.sinkTo(
                        new Elasticsearch8AsyncSinkBuilder<String>()
                                .setHosts(new HttpHost("siem-elasticsearch", 9200, "http"))
                                .setMaxBatchSize(500)                 // 批量大小
                                .setMaxInFlightRequests(5)
                                .setMaxBufferedRequests(1000)
                                .setMaxTimeInBufferMS(1000)
                                .setElementConverter((element, context) ->
                                        new IndexOperation.Builder<JsonData>()
                                                .index("siem-alerts")
                                                .id(alertId(element))       // 确定性 _id:重放变幂等 upsert(Phase 3.0-F2)
                                                .document(JsonData.fromJson(element))   // element 是告警 JSON 字符串
                                                .build()
                                )
                                .build()
                )
                .uid("es-sink");


        env.execute(
                "SIEM Detection Engine"
        );
    }

    /**
     * 计算告警的确定性 _id:sha1(rule_id + 实体 + 事件时间)。
     * 同一事件被重放时计算得到相同 _id,ES 写入变为幂等覆盖,避免重复告警。
     */
    private static String alertId(String element) {
        try {
            Map<String, Object> alert = ALERT_MAPPER.readValue(element, Map.class);
            String ruleId = String.valueOf(alert.getOrDefault("alert.rule_id", "unknown"));
            Object ip = alert.get("source.ip");
            Object user = alert.get("user.name");
            String entity = ip != null ? String.valueOf(ip)
                    : (user != null ? String.valueOf(user) : "unknown");
            String ts = String.valueOf(alert.getOrDefault("@timestamp", "unknown"));
            return sha1Hex(ruleId + "|" + entity + "|" + ts);
        } catch (Exception e) {
            return sha1Hex(element);
        }
    }

    private static String sha1Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
