package com.xscsiem.hsiem_platform.soar;

import java.util.List;
import java.util.Map;

/** 管理员通过 Git/YAML 注册的连接器；凭据只保存环境变量引用。 */
public record SoarConnector(
        String id,
        String name,
        String description,
        Boolean enabled,
        String baseUrl,
        String baseUrlEnv,
        Boolean allowPrivateNetwork,
        Boolean allowInsecureHttp,
        Auth auth,
        Tls tls,
        Limits limits,
        Map<String, Action> actions) {

    public SoarConnector(String id, String name, String description, Boolean enabled,
                         String baseUrl, String baseUrlEnv, Boolean allowPrivateNetwork,
                         Boolean allowInsecureHttp, Auth auth, Map<String, Action> actions) {
        this(id, name, description, enabled, baseUrl, baseUrlEnv, allowPrivateNetwork,
                allowInsecureHttp, auth, null, null, actions);
    }

    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }

    public record Auth(String type, String secretEnv, String secretRef, String header) {
        public Auth(String type, String secretEnv, String header) {
            this(type, secretEnv, null, header);
        }
        public String reference() {
            return secretRef != null && !secretRef.isBlank() ? secretRef
                    : secretEnv == null ? null : "env://" + secretEnv;
        }
    }

    /** PKCS12 内容使用 secret ref 提供 base64，私钥和信任材料不会进入 Git。 */
    public record Tls(Boolean mtls, String keyStoreRef, String keyStorePasswordRef,
                      String trustStoreRef, String trustStorePasswordRef) {
    }

    /** 分布式配额/熔断 + 每实例 bulkhead 上限。 */
    public record Limits(Integer perMinute, Integer perDay, Integer maxConcurrent,
                         Integer failureThreshold, Integer circuitOpenSeconds) {
    }

    public record Action(String method, String path, Integer timeoutSeconds,
                         List<String> required, Integer maxResponseBytes) {
    }
}
