package com.siem;

import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.serialization.SimpleStringSchema;

import org.apache.flink.connector.elasticsearch.sink.Elasticsearch8AsyncSinkBuilder;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;

import co.elastic.clients.elasticsearch.core.bulk.IndexOperation;
import co.elastic.clients.json.JsonData;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.windowing.assigners.TumblingEventTimeWindows;
import org.apache.http.HttpHost;

import java.time.Duration;


public class DetectionJob {

    public static void main(String[] args) throws Exception {

        StreamExecutionEnvironment env =
                StreamExecutionEnvironment.getExecutionEnvironment();

        // 开启 checkpointing:
        // 1) KafkaSource 的 offset 随 checkpoint 提交,重启不会从 earliest 重放历史(避免重复告警)
        // 2) 时间窗口规则(有状态)获得故障恢复能力
        env.enableCheckpointing(60_000);
        env.getCheckpointConfig().setMinPauseBetweenCheckpoints(30_000);

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
                );

        // 解析事件:扁平字段 + 事件时间戳(供事件时间窗口使用)
        DataStream<Event> parsed = events.map(EventParser::parseEvent);


        /*
         * 1) 单事件规则:逐规则匹配(RuleRegistry)→ 生成符合 Alert Schema 的告警 JSON。
         */
        DataStream<String> singleAlerts =
                parsed.flatMap(new DetectionFunction(new RuleRegistry()));


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
                5, 5);

        DataStream<String> windowAlerts =
                parsed
                        .assignTimestampsAndWatermarks(
                                WatermarkStrategy.<Event>forBoundedOutOfOrderness(Duration.ofSeconds(10))
                                        .withTimestampAssigner((e, ts) -> e.getTimestampMillis())
                        )
                        .keyBy(e -> String.valueOf(
                                e.getFields().getOrDefault(bruteForce.getKeyField(), "unknown")))
                        .window(TumblingEventTimeWindows.of(
                                Duration.ofMinutes(bruteForce.getWindowMinutes())))
                        .process(new WindowRuleFunction(bruteForce));


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
                                        .document(JsonData.fromJson(element))   // element 是告警 JSON 字符串
                                        .build()
                        )
                        .build()
        );


        env.execute(
                "SIEM Detection Engine"
        );
    }
}
