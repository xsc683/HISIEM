package com.xscsiem.hsiem_platform.soar;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.xscsiem.hsiem_platform.onboarding.NotFoundException;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 连接器目录和动作 Schema；加载阶段拒绝动态 URL、任意 Header 和未知 HTTP 方法。 */
@Component
public class SoarConnectorRegistry {

    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{2,127}");
    private static final Pattern ENV = Pattern.compile("[A-Z_][A-Z0-9_]{1,127}");
    private static final Pattern PATH_VARIABLE = Pattern.compile("\\$\\{([a-zA-Z0-9_.-]+)}");
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
    private final Path directory;
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory()).findAndRegisterModules();
    private volatile Map<String, SoarConnector> connectors = Map.of();

    public SoarConnectorRegistry(
            @Value("${app.soar.connectors-dir:infra/soar/connectors}") String directory) {
        this.directory = Path.of(directory).toAbsolutePath().normalize();
    }

    @PostConstruct
    public void initialize() {
        reload();
    }

    public List<SoarConnector> list() {
        return new ArrayList<>(connectors.values());
    }

    public SoarConnector get(String id) {
        SoarConnector connector = connectors.get(id);
        if (connector == null) throw new NotFoundException("SOAR 连接器不存在: " + id);
        return connector;
    }

    public synchronized List<SoarConnector> reload() {
        if (!Files.isDirectory(directory)) {
            connectors = Map.of();
            return List.of();
        }
        Map<String, SoarConnector> loaded = new LinkedHashMap<>();
        try (var files = Files.list(directory)) {
            for (Path path : files.filter(Files::isRegularFile)
                    .filter(item -> item.getFileName().toString().matches(".*\\.ya?ml"))
                    .sorted().toList()) {
                SoarConnector connector = yaml.readValue(path.toFile(), SoarConnector.class);
                validate(connector, path);
                if (loaded.putIfAbsent(connector.id(), connector) != null) {
                    throw new IllegalArgumentException("SOAR 连接器 ID 重复: " + connector.id());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("读取 SOAR 连接器失败: " + e.getMessage(), e);
        }
        connectors = Collections.unmodifiableMap(new LinkedHashMap<>(loaded));
        return list();
    }

    static void validate(SoarConnector connector, Path source) {
        String label = source == null ? "connector" : source.toString();
        if (connector == null || connector.id() == null || !ID.matcher(connector.id()).matches()) {
            throw new IllegalArgumentException(label + " 的 connector.id 非法");
        }
        if (blank(connector.name()) || blank(connector.baseUrl()) == blank(connector.baseUrlEnv())) {
            throw new IllegalArgumentException(connector.id() + " 必须设置 name，且 baseUrl/baseUrlEnv 二选一");
        }
        if (!blank(connector.baseUrlEnv()) && !ENV.matcher(connector.baseUrlEnv()).matches()) {
            throw new IllegalArgumentException(connector.id() + " 的 baseUrlEnv 非法");
        }
        if (!blank(connector.baseUrl())) validateBaseUrl(connector);
        if (connector.actions() == null || connector.actions().isEmpty()) {
            throw new IllegalArgumentException(connector.id() + " 至少声明一个 action");
        }
        connector.actions().forEach((id, action) -> {
            if (!ID.matcher(id).matches() || action == null || !METHODS.contains(upper(action.method()))
                    || blank(action.path()) || !action.path().startsWith("/")
                    || action.path().contains("://")) {
                throw new IllegalArgumentException(connector.id() + "/" + id + " 的动作 Schema 非法");
            }
            int timeout = action.timeoutSeconds() == null ? 10 : action.timeoutSeconds();
            int maxBytes = action.maxResponseBytes() == null ? 1_048_576 : action.maxResponseBytes();
            if (timeout < 1 || timeout > 60 || maxBytes < 1024 || maxBytes > 5_242_880) {
                throw new IllegalArgumentException(connector.id() + "/" + id + " 的超时或响应上限非法");
            }
            Set<String> required = action.required() == null ? Set.of() : Set.copyOf(action.required());
            var matcher = PATH_VARIABLE.matcher(action.path());
            while (matcher.find()) {
                if (!required.contains(matcher.group(1))) {
                    throw new IllegalArgumentException(connector.id() + "/" + id
                            + " 的路径变量必须列入 required: " + matcher.group(1));
                }
            }
        });
        if (connector.auth() != null && !Set.of("none", "bearer", "api-key")
                .contains(connector.auth().type())) {
            throw new IllegalArgumentException(connector.id() + " 的 auth.type 非法");
        }
        if (connector.auth() != null && !"none".equals(connector.auth().type())
                && (blank(connector.auth().secretEnv())
                || !ENV.matcher(connector.auth().secretEnv()).matches())) {
            throw new IllegalArgumentException(connector.id() + " 的凭据环境变量引用非法");
        }
    }

    private static void validateBaseUrl(SoarConnector connector) {
        try {
            URI uri = URI.create(connector.baseUrl());
            boolean allowedScheme = "https".equalsIgnoreCase(uri.getScheme())
                    || Boolean.TRUE.equals(connector.allowInsecureHttp())
                    && "http".equalsIgnoreCase(uri.getScheme());
            if (!allowedScheme || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getFragment() != null || uri.getQuery() != null) {
                throw new IllegalArgumentException();
            }
        } catch (Exception e) {
            throw new IllegalArgumentException(connector.id() + " 的固定 baseUrl 非法或不安全");
        }
    }

    private static String upper(String value) {
        return value == null ? "" : value.toUpperCase();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
