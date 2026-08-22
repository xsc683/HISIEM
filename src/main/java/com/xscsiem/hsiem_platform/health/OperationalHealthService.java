package com.xscsiem.hsiem_platform.health;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
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
                                    @Value("${app.health.scan.kafka-port:9092}") int kafkaPort) {
        this.jdbc = jdbc;
        this.elasticsearch = elasticsearch;
        this.objectMapper = objectMapper;
        this.flinkUrl = flinkUrl;
        this.kibanaUrl = kibanaUrl;
        this.logstashUrl = logstashUrl;
        this.kafkaHost = kafkaHost;
        this.kafkaPort = kafkaPort;
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
            components.put("kafka", tcp("kafka", kafkaHost, kafkaPort));
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
}
