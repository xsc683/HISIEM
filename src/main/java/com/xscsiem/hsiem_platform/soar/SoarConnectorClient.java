package com.xscsiem.hsiem_platform.soar;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;

import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** 固定基址连接器 Runner：禁止输入 URL，限制 DNS、重定向、超时和响应体。 */
@Component
public class SoarConnectorClient {

    private static final Pattern VARIABLE = Pattern.compile("\\$\\{([a-zA-Z0-9_.-]+)}");
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private final SoarConnectorRegistry registry;
    private final SoarHttpClientFactory clients;
    private final SoarSecretResolver secrets;
    private final SoarConnectorGuard guard;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService isolationPool = Executors.newVirtualThreadPerTaskExecutor();

    public SoarConnectorClient(SoarConnectorRegistry registry, SoarHttpClientFactory clients,
                               SoarSecretResolver secrets, SoarConnectorGuard guard) {
        this.registry = registry;
        this.clients = clients;
        this.secrets = secrets;
        this.guard = guard;
    }

    public Map<String, Object> call(String connectorId, String operation,
                                    Map<String, Object> arguments) {
        return call("default", connectorId, operation, arguments, null);
    }

    public Map<String, Object> call(String tenantId, String connectorId, String operation,
                                    Map<String, Object> arguments, String executionId) {
        SoarConnector connector = registry.get(connectorId);
        if (!connector.isEnabled()) throw new IllegalStateException("SOAR 连接器未启用: " + connectorId);
        SoarConnector.Action action = connector.actions().get(operation);
        if (action == null) throw new IllegalArgumentException("连接器动作不存在: " + connectorId + "/" + operation);
        Map<String, Object> args = arguments == null ? Map.of() : arguments;
        for (String required : action.required() == null ? List.<String>of() : action.required()) {
            if (!args.containsKey(required) || String.valueOf(args.get(required)).isBlank()) {
                throw new IllegalArgumentException("连接器动作缺少参数: " + required);
            }
        }
        int timeout = action.timeoutSeconds() == null ? 10 : action.timeoutSeconds();
        Duration timeoutDuration = Duration.ofSeconds(timeout);
        SoarConnectorGuard.Permit permit;
        try {
            permit = guard.acquire(tenantId, connector, timeoutDuration);
        } catch (SoarConnectorGuard.ConnectorRejectedException rejected) {
            guard.rejected(tenantId, connectorId, operation, executionId, rejected.code());
            throw rejected;
        }
        long started = System.nanoTime();
        Future<Map<String, Object>> isolated = isolationPool.submit(
                () -> invoke(connectorId, operation, connector, action, args));
        try {
            Map<String, Object> result = isolated.get(timeout + 1L, TimeUnit.SECONDS);
            guard.success(permit, operation, executionId, elapsed(started));
            return result;
        } catch (Exception e) {
            isolated.cancel(true);
            Throwable cause = e.getCause() == null ? e : e.getCause();
            guard.failure(permit, connector, operation, executionId, elapsed(started), errorCode(cause));
            if (cause instanceof IllegalArgumentException argument) throw argument;
            if (cause instanceof IllegalStateException state) throw state;
            throw new IllegalStateException("连接器调用失败: " + cause.getMessage(), cause);
        }
    }

    private Map<String, Object> invoke(String connectorId, String operation, SoarConnector connector,
                                       SoarConnector.Action action, Map<String, Object> args) {
        try {
            URI base = URI.create(baseUrl(connector));
            validateBase(base, connector);
            String renderedPath = renderPath(action.path(), args);
            URI uri = base.resolve(renderedPath);
            if (!base.getScheme().equalsIgnoreCase(uri.getScheme())
                    || !base.getHost().equalsIgnoreCase(uri.getHost())
                    || effectivePort(base) != effectivePort(uri)) {
                throw new IllegalArgumentException("连接器路径越过固定基址");
            }
            int timeout = action.timeoutSeconds() == null ? 10 : action.timeoutSeconds();
            HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(timeout));
            applyAuth(request, connector.auth());
            String method = action.method().toUpperCase();
            if ("GET".equals(method) || "DELETE".equals(method)) {
                request.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                request.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(args)));
            }
            HttpClient client = clients.create(connector);
            HttpResponse<byte[]> response = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            int maxBytes = action.maxResponseBytes() == null ? 1_048_576 : action.maxResponseBytes();
            if (response.body().length > maxBytes) throw new IllegalStateException("连接器响应超过上限");
            String body = new String(response.body(), StandardCharsets.UTF_8);
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("连接器返回 HTTP " + response.statusCode());
            }
            Map<String, Object> result;
            try {
                result = body.isBlank() ? new LinkedHashMap<>() : mapper.readValue(body, MAP);
            } catch (Exception ignored) {
                result = new LinkedHashMap<>();
                result.put("text", body);
            }
            result.put("httpStatus", response.statusCode());
            result.put("connector", connectorId);
            result.put("operation", operation);
            return result;
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("连接器调用失败: " + e.getMessage(), e);
        }
    }

    private static String baseUrl(SoarConnector connector) {
        String value = connector.baseUrl();
        if (value == null || value.isBlank()) value = System.getenv(connector.baseUrlEnv());
        if (value == null || value.isBlank()) throw new IllegalStateException("连接器 baseUrl 环境变量未配置");
        return value.endsWith("/") ? value : value + "/";
    }

    private static void validateBase(URI uri, SoarConnector connector) throws Exception {
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("连接器 baseUrl 非法");
        }
        boolean https = "https".equalsIgnoreCase(uri.getScheme());
        if (!https && !(Boolean.TRUE.equals(connector.allowInsecureHttp())
                && "http".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException("连接器必须使用 HTTPS");
        }
        if (!Boolean.TRUE.equals(connector.allowPrivateNetwork())) {
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                        || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                        || address.isMulticastAddress() || additionalPrivateRange(address)) {
                    throw new IllegalArgumentException("连接器禁止访问私有或本地地址");
                }
            }
        }
    }

    private static boolean additionalPrivateRange(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            return first == 100 && second >= 64 && second <= 127; // RFC 6598 CGNAT
        }
        return bytes.length == 16 && (Byte.toUnsignedInt(bytes[0]) & 0xfe) == 0xfc; // IPv6 ULA
    }

    private static String renderPath(String template, Map<String, Object> arguments) {
        Matcher matcher = VARIABLE.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            Object value = arguments.get(matcher.group(1));
            if (value == null) throw new IllegalArgumentException("连接器路径缺少参数: " + matcher.group(1));
            matcher.appendReplacement(out, Matcher.quoteReplacement(
                    URLEncoder.encode(String.valueOf(value), StandardCharsets.UTF_8).replace("+", "%20")));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private void applyAuth(HttpRequest.Builder request, SoarConnector.Auth auth) {
        if (auth == null || "none".equals(auth.type())) return;
        String secret = secrets.resolve(auth.reference());
        if ("bearer".equals(auth.type())) request.header("Authorization", "Bearer " + secret);
        else request.header(auth.header() == null ? "X-API-Key" : auth.header(), secret);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static long elapsed(long started) {
        return Math.max(0, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }

    private static String errorCode(Throwable error) {
        if (error instanceof java.util.concurrent.TimeoutException) return "TIMEOUT";
        if (error instanceof java.net.http.HttpTimeoutException) return "HTTP_TIMEOUT";
        if (error instanceof java.io.IOException) return "IO_ERROR";
        return "CONNECTOR_ERROR";
    }

    @PreDestroy
    public void close() {
        isolationPool.shutdownNow();
    }
}
