package com.xscsiem.hsiem_platform.health;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/** 对 Docker Desktop + WSL2 数据面做一次可重复的运行态扫描。 */
@Service
public class OperationalHealthService {

    private final JdbcTemplate jdbc;
    private final ElasticsearchClient elasticsearch;
    private final ObjectMapper objectMapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final String flinkUrl;
    private final String kibanaUrl;
    private final String logstashUrl;
    private final String kafkaHost;
    private final int kafkaPort;
    private final String kafkaTopic;
    private final String kafkaGroup;
    private final long kafkaMaxLag;
    private final String kafkaSecurityProtocol;
    private final String kafkaSaslMechanism;
    private final String kafkaSaslJaasConfig;
    private final String kafkaTruststoreLocation;
    private final String kafkaTruststorePassword;
    private final Counter scanCounter;
    private final Timer scanTimer;
    private volatile long lastScanEpoch;

    public OperationalHealthService(JdbcTemplate jdbc, ElasticsearchClient elasticsearch,
                                    ObjectMapper objectMapper,
                                    MeterRegistry metrics,
                                    @Value("${app.health.scan.flink-url:http://localhost:8081}") String flinkUrl,
                                    @Value("${app.health.scan.kibana-url:http://localhost:5601}") String kibanaUrl,
                                    @Value("${app.health.scan.logstash-url:http://localhost:9600}") String logstashUrl,
                                    @Value("${app.health.scan.kafka-host:localhost}") String kafkaHost,
                                    @Value("${app.health.scan.kafka-port:9092}") int kafkaPort,
                                    @Value("${app.health.scan.kafka-topic:siem-events}") String kafkaTopic,
                                    @Value("${app.health.scan.kafka-group:siem-detection}") String kafkaGroup,
                                    @Value("${app.health.scan.kafka-max-lag:10000}") long kafkaMaxLag,
                                    @Value("${app.health.scan.kafka-security-protocol:PLAINTEXT}") String kafkaSecurityProtocol,
                                    @Value("${app.health.scan.kafka-sasl-mechanism:}") String kafkaSaslMechanism,
                                    @Value("${app.health.scan.kafka-sasl-jaas-config:}") String kafkaSaslJaasConfig,
                                    @Value("${app.health.scan.kafka-ssl-truststore-location:}") String kafkaTruststoreLocation,
                                    @Value("${app.health.scan.kafka-ssl-truststore-password:}") String kafkaTruststorePassword) {
        this.jdbc = jdbc;
        this.elasticsearch = elasticsearch;
        this.objectMapper = objectMapper;
        this.flinkUrl = flinkUrl;
        this.kibanaUrl = kibanaUrl;
        this.logstashUrl = logstashUrl;
        this.kafkaHost = kafkaHost;
        this.kafkaPort = kafkaPort;
        this.kafkaTopic = kafkaTopic;
        this.kafkaGroup = kafkaGroup;
        this.kafkaMaxLag = kafkaMaxLag;
        this.kafkaSecurityProtocol = kafkaSecurityProtocol;
        this.kafkaSaslMechanism = kafkaSaslMechanism;
        this.kafkaSaslJaasConfig = kafkaSaslJaasConfig;
        this.kafkaTruststoreLocation = kafkaTruststoreLocation;
        this.kafkaTruststorePassword = kafkaTruststorePassword;
        this.scanCounter = Counter.builder("siem.health.scans").description("Health scans executed").register(metrics);
        this.scanTimer = Timer.builder("siem.health.scan.duration").description("Health scan duration").register(metrics);
        io.micrometer.core.instrument.Gauge.builder("siem.health.last.scan.epoch", this, value -> value.lastScanEpoch)
                .description("Epoch seconds of the last health scan").register(metrics);
    }

    public Map<String, Object> scan() {
        return scanTimer.record(() -> {
            scanCounter.increment();
            lastScanEpoch = Instant.now().getEpochSecond();
            LinkedHashMap<String, Map<String, Object>> components = new LinkedHashMap<>();
            components.put("postgresql", database());
            components.put("elasticsearch", elasticsearch());
            components.put("kafka", kafka());
            components.put("logstash", logstash());
            components.put("flink", httpJson("flink", flinkUrl + "/overview",
                    node -> node.path("jobs-running").isInt() && node.path("jobs-running").asInt() > 0,
                    "Flink 没有运行中的检测任务"));
            components.put("kibana", httpJson("kibana", kibanaUrl + "/api/status",
                    node -> "available".equalsIgnoreCase(node.at("/status/overall/level").asText()),
                    "Kibana overall status 非 available"));
            boolean healthy = components.values().stream().allMatch(row -> "UP".equals(row.get("status")));
            return new LinkedHashMap<>(Map.of(
                    "status", healthy ? "UP" : "DOWN",
                    "scannedAt", Instant.ofEpochSecond(lastScanEpoch).toString(),
                    "components", components,
                    "metrics", Map.of("scans", scanCounter.count(), "lastScanEpoch", lastScanEpoch)));
        });
    }

    private Map<String, Object> database() {
        long started = System.nanoTime();
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            return row("postgresql", "UP", elapsedMs(started), null);
        } catch (Exception e) {
            return row("postgresql", "DOWN", elapsedMs(started), e.getMessage());
        }
    }

    /** Kafka 不再只探测 TCP；同时确认 topic、consumer group 和积压量。 */
    private Map<String, Object> kafka() {
        long started = System.nanoTime();
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaHost + ":" + kafkaPort);
        props.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "3000");
        props.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "3000");
        if (kafkaSecurityProtocol != null && !kafkaSecurityProtocol.isBlank()) {
            props.put("security.protocol", kafkaSecurityProtocol);
        }
        putIfPresent(props, "sasl.mechanism", kafkaSaslMechanism);
        putIfPresent(props, "sasl.jaas.config", kafkaSaslJaasConfig);
        putIfPresent(props, "ssl.truststore.location", kafkaTruststoreLocation);
        putIfPresent(props, "ssl.truststore.password", kafkaTruststorePassword);
        try (AdminClient admin = AdminClient.create(props)) {
            var description = admin.describeTopics(List.of(kafkaTopic)).allTopicNames()
                    .get(4, TimeUnit.SECONDS).get(kafkaTopic);
            if (description == null || description.partitions().isEmpty()) {
                return row("kafka", "DOWN", elapsedMs(started), "topic 不存在或没有分区");
            }
            List<TopicPartition> partitions = description.partitions().stream()
                    .map(partition -> new TopicPartition(kafkaTopic, partition.partition()))
                    .toList();
            Map<TopicPartition, OffsetSpec> requests = new HashMap<>();
            partitions.forEach(partition -> requests.put(partition, OffsetSpec.latest()));
            Map<TopicPartition, Long> end = admin.listOffsets(requests).all()
                    .get(4, TimeUnit.SECONDS).entrySet().stream()
                    .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, e -> e.getValue().offset()));
            Map<TopicPartition, OffsetAndMetadata> committed = admin.listConsumerGroupOffsets(kafkaGroup)
                    .partitionsToOffsetAndMetadata().get(4, TimeUnit.SECONDS);
            long lag = 0;
            int missing = 0;
            for (TopicPartition partition : partitions) {
                Long latest = end.get(partition);
                OffsetAndMetadata offset = committed.get(partition);
                if (latest != null && offset != null) lag += Math.max(0, latest - offset.offset());
                if (offset == null) missing++;
            }
            Map<String, Object> result = row("kafka", missing == partitions.size() || lag > kafkaMaxLag ? "DOWN" : "UP",
                    elapsedMs(started), missing == partitions.size() ? "consumer group 尚未提交 offset"
                            : lag > kafkaMaxLag ? "consumer lag 超过阈值 " + kafkaMaxLag : null);
            result.put("topic", kafkaTopic);
            result.put("consumerGroup", kafkaGroup);
            result.put("partitions", partitions.size());
            result.put("lag", lag);
            return result;
        } catch (Exception e) {
            return row("kafka", "DOWN", elapsedMs(started), e.getMessage());
        }
    }

    private Map<String, Object> elasticsearch() {
        long started = System.nanoTime();
        try {
            boolean up = elasticsearch.ping().value();
            return row("elasticsearch", up ? "UP" : "DOWN", elapsedMs(started), up ? null : "ping=false");
        } catch (Exception e) {
            return row("elasticsearch", "DOWN", elapsedMs(started), e.getMessage());
        }
    }

    private Map<String, Object> logstash() {
        URI endpoint = URI.create(logstashUrl);
        Map<String, Object> monitoring = httpJson("logstash", logstashUrl + "/_node/pipelines",
                node -> node.path("pipelines").isObject() && node.path("pipelines").size() > 0,
                "Logstash 没有活动 pipeline");
        if ("UP".equals(monitoring.get("status"))) return monitoring;

        // Logstash monitoring API 在部分镜像配置下会 reset 连接；保留 TCP 结果，但明确这是降级探针。
        Map<String, Object> socket = tcp("logstash", endpoint.getHost(), endpoint.getPort() > 0 ? endpoint.getPort() : 9600);
        if ("UP".equals(socket.get("status"))) {
            socket.put("probe", "tcp");
            socket.put("degraded", true);
            socket.put("warning", "监控 API 不可用，仅确认端口监听");
        }
        return socket;
    }

    private Map<String, Object> httpJson(String name, String url,
                                         Predicate<JsonNode> healthy,
                                         String unhealthyMessage) {
        long started = System.nanoTime();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3)).GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return row(name, "DOWN", elapsedMs(started), "HTTP " + response.statusCode());
            }
            JsonNode body = objectMapper.readTree(response.body());
            boolean isHealthy = healthy.test(body);
            return row(name, isHealthy ? "UP" : "DOWN", elapsedMs(started),
                    isHealthy ? null : unhealthyMessage);
        } catch (Exception e) {
            return row(name, "DOWN", elapsedMs(started), e.getMessage());
        }
    }

    private Map<String, Object> tcp(String name, String host, int port) {
        long started = System.nanoTime();
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new InetSocketAddress(host, port), 2000);
            return row(name, "UP", elapsedMs(started), null);
        } catch (Exception e) {
            return row(name, "DOWN", elapsedMs(started), e.getMessage());
        }
    }

    private static Map<String, Object> row(String name, String status, long latencyMs, String error) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", name);
        row.put("status", status);
        row.put("latencyMs", latencyMs);
        if (error != null) row.put("error", error);
        return row;
    }

    private static long elapsedMs(long started) {
        return Math.max(0, Duration.ofNanos(System.nanoTime() - started).toMillis());
    }

    private static void putIfPresent(Properties props, String key, String value) {
        if (value != null && !value.isBlank()) props.put(key, value);
    }
}
