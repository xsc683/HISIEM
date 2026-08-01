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
         * Detection Rule:
         *
         * authentication_failure
         *
         */

        DataStream<String> alerts =
                events
                        .filter(
                                json -> json.contains(
                                        "authentication_failure"
                                )
                        )
                        .map(
                                json -> String.format(
                                        """
                                        {
                                          "alert_type":"ssh_authentication_failure",
                                          "severity":"medium",
                                          "description":"SSH authentication failure detected",
                                          "raw_event":%s
                                        }
                                        """,
                                        json
                                )
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
                                        .document(JsonData.fromJson(element))   // element 是 JSON 字符串
                                        .build()
                        )
                        .build()
        );


        env.execute(
                "SIEM Detection Engine"
        );
    }
}
