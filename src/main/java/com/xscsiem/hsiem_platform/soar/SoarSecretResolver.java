package com.xscsiem.hsiem_platform.soar;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** env、Vault KV v2 和 Vault Transit(KMS) 的统一 secret ref 解析器。 */
@Component
public class SoarSecretResolver {

    private static final Pattern SAFE_PATH = Pattern.compile("[a-zA-Z0-9_./-]{1,512}");
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private final URI vaultAddress;
    private final String vaultTokenEnv;
    private final String vaultNamespace;
    private final Duration cacheTtl;
    private final HttpClient client;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, CachedSecret> cache = new ConcurrentHashMap<>();

    public SoarSecretResolver(
            @Value("${app.soar.secrets.vault-address:}") String vaultAddress,
            @Value("${app.soar.secrets.vault-token-env:SIEM_VAULT_TOKEN}") String vaultTokenEnv,
            @Value("${app.soar.secrets.vault-namespace:}") String vaultNamespace,
            @Value("${app.soar.secrets.cache-ttl:PT1M}") Duration cacheTtl,
            @Value("${app.soar.outbound.proxy-host:}") String proxyHost,
            @Value("${app.soar.outbound.proxy-port:0}") int proxyPort) {
        this.vaultAddress = vaultAddress == null || vaultAddress.isBlank() ? null
                : URI.create(vaultAddress.endsWith("/") ? vaultAddress : vaultAddress + "/");
        if (this.vaultAddress != null && !"https".equalsIgnoreCase(this.vaultAddress.getScheme())) {
            throw new IllegalArgumentException("Vault 地址必须使用 HTTPS");
        }
        this.vaultTokenEnv = vaultTokenEnv;
        this.vaultNamespace = vaultNamespace;
        this.cacheTtl = cacheTtl.isNegative() ? Duration.ZERO : cacheTtl;
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER);
        if (proxyHost != null && !proxyHost.isBlank()) {
            if (proxyPort < 1 || proxyPort > 65535) throw new IllegalArgumentException("SOAR proxy port 非法");
            builder.proxy(ProxySelector.of(new InetSocketAddress(proxyHost, proxyPort)));
        }
        this.client = builder.build();
    }

    public String resolve(String reference) {
        if (reference == null || reference.isBlank()) throw new IllegalArgumentException("secret ref 不能为空");
        CachedSecret cached = cache.get(reference);
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) return cached.value();
        String value = reference.startsWith("env://")
                ? environment(reference.substring("env://".length()))
                : reference.startsWith("vault://") ? vaultKv(reference)
                : reference.startsWith("vault-transit://") ? vaultTransit(reference)
                : throwInvalid(reference);
        cache.put(reference, new CachedSecret(value, Instant.now().plus(cacheTtl)));
        return value;
    }

    private String vaultKv(String reference) {
        String spec = reference.substring("vault://".length());
        int fragment = spec.lastIndexOf('#');
        if (fragment <= 0 || fragment == spec.length() - 1) throw new IllegalArgumentException("Vault ref 缺少 #field");
        String path = spec.substring(0, fragment);
        String field = spec.substring(fragment + 1);
        if (!SAFE_PATH.matcher(path).matches() || !SAFE_PATH.matcher(field).matches()) {
            throw new IllegalArgumentException("Vault ref 路径非法");
        }
        int slash = path.indexOf('/');
        if (slash <= 0 || slash == path.length() - 1) {
            throw new IllegalArgumentException("Vault KV ref 需为 mount/path#field");
        }
        String mount = path.substring(0, slash);
        String secretPath = path.substring(slash + 1);
        Map<String, Object> response = vaultRequest("v1/" + encode(mount)
                + "/data/" + encodePath(secretPath), null);
        Object data = response.get("data");
        if (data instanceof Map<?, ?> outer && outer.get("data") instanceof Map<?, ?> inner) {
            Object value = inner.get(field);
            if (value != null) return String.valueOf(value);
        }
        throw new IllegalStateException("Vault KV 字段不存在");
    }

    private String vaultTransit(String reference) {
        String spec = reference.substring("vault-transit://".length());
        int fragment = spec.lastIndexOf('#');
        if (fragment <= 0 || fragment == spec.length() - 1) {
            throw new IllegalArgumentException("Vault Transit ref 需为 key#ciphertextEnv");
        }
        String key = spec.substring(0, fragment);
        String ciphertext = environment(spec.substring(fragment + 1));
        if (!SAFE_PATH.matcher(key).matches()) throw new IllegalArgumentException("Transit key 非法");
        Map<String, Object> response = vaultRequest("v1/transit/decrypt/" + encode(key),
                Map.of("ciphertext", ciphertext));
        Object data = response.get("data");
        if (data instanceof Map<?, ?> values && values.get("plaintext") != null) {
            return new String(Base64.getDecoder().decode(String.valueOf(values.get("plaintext"))),
                    StandardCharsets.UTF_8);
        }
        throw new IllegalStateException("Vault Transit 未返回明文");
    }

    private Map<String, Object> vaultRequest(String path, Map<String, Object> body) {
        if (vaultAddress == null) throw new IllegalStateException("未配置 app.soar.secrets.vault-address");
        String token = environment(vaultTokenEnv);
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(vaultAddress.resolve(path))
                    .timeout(Duration.ofSeconds(10)).header("X-Vault-Token", token);
            if (vaultNamespace != null && !vaultNamespace.isBlank()) {
                request.header("X-Vault-Namespace", vaultNamespace);
            }
            if (body == null) request.GET();
            else request.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("Vault 返回 HTTP " + response.statusCode());
            }
            return mapper.readValue(response.body(), MAP);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Vault 请求失败", e);
        }
    }

    private static String environment(String name) {
        if (name == null || !name.matches("[A-Z_][A-Z0-9_]{1,127}")) {
            throw new IllegalArgumentException("环境变量引用非法");
        }
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("secret 环境变量未配置: " + name);
        return value;
    }

    private static String encodePath(String path) {
        return java.util.Arrays.stream(path.split("/", -1)).map(SoarSecretResolver::encode)
                .reduce((a, b) -> a + "/" + b).orElse("");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String throwInvalid(String reference) {
        throw new IllegalArgumentException("不支持的 secret ref scheme: " + reference.split(":", 2)[0]);
    }

    private record CachedSecret(String value, Instant expiresAt) {
    }
}
