package com.siem;

import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.serialization.SimpleStringSchema;

import org.apache.flink.connector.elasticsearch.sink.Elasticsearch8AsyncSinkBuilder;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;

import co.elastic.clients.elasticsearch.core.bulk.IndexOperation;
import co.elastic.clients.json.JsonData;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.http.HttpHost;


public class DetectionJob {

    public static void main(String[] args) throws Exception {

        StreamExecutionEnvironment env =
                StreamExecutionEnvironment.getExecutionEnvironment();


        KafkaSource<String> source =
                KafkaSource.<String>builder()
                        .setBootstrapServers("kafka:9092")
                        .setTopics("siem-events")
                        .setGroupId("siem-detection")
                        .setStartingOffsets(
                                OffsetsInitializer.earliest()
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


        /*
         * 规则引擎:解析事件 → 逐规则匹配(RuleRegistry)→ 生成符合 Alert Schema 的告警 JSON。
         *
         * 告警为扁平结构(决策 D):关键事件字段提升到告警顶层,完整事件存为 event.raw 扁平字符串。
         */

        DataStream<String> alerts =
                events.flatMap(
                        new DetectionFunction(new RuleRegistry())
                );


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
