package com.xscsiem.hsiem_platform.health;

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

/** 对 Docker Desktop + WSL2 数据面做一次可重复的运行态扫描。 */
@Service
public class OperationalHealthService {

    private final JdbcTemplate jdbc;
    private final ElasticsearchClient elasticsearch;
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
                                    MeterRegistry metrics,
                                    @Value("${app.health.scan.flink-url:http://localhost:8081}") String flinkUrl,
                                    @Value("${app.health.scan.kibana-url:http://localhost:5601}") String kibanaUrl,
                                    @Value("${app.health.scan.logstash-url:http://localhost:9600}") String logstashUrl,
                                    @Value("${app.health.scan.kafka-host:localhost}") String kafkaHost,
                                    @Value("${app.health.scan.kafka-port:9092}") int kafkaPort) {
        this.jdbc = jdbc;
        this.elasticsearch = elasticsearch;
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
            // Logstash 8.14 的 monitoring API 在当前 compose 配置下可能接受连接后 reset，
            // 与部署 healthcheck 保持一致，用 TCP 监听作为可用性判据。
            URI logstash = URI.create(logstashUrl);
            components.put("logstash", tcp("logstash", logstash.getHost(), logstash.getPort()));
            components.put("flink", http("flink", flinkUrl + "/overview"));
            components.put("kibana", http("kibana", kibanaUrl + "/api/status"));
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

    private Map<String, Object> http(String name, String url) {
        long started = System.nanoTime();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3)).GET().build();
            int status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            return row(name, status < 500 ? "UP" : "DOWN", elapsedMs(started),
                    status < 500 ? null : "HTTP " + status);
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
