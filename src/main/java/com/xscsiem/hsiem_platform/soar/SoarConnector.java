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
        Map<String, Action> actions) {

    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }

    public record Auth(String type, String secretEnv, String header) {
    }

    public record Action(String method, String path, Integer timeoutSeconds,
                         List<String> required, Integer maxResponseBytes) {
    }
}
