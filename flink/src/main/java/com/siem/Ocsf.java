package com.siem;

import java.util.Map;

/**
 * OCSF 可移植视图(Phase 3.2):在 ECS 存储的告警上附加 OCSF 核心字段,
 * 使规则/看板未来换平台或对接 AWS Security Lake 时不必重写。
 * 存储仍以 ECS 为准,此为输出侧补充视图;完整映射见 docs/design/ocsf-mapping.md。
 */
public final class Ocsf {

    private Ocsf() {
    }

    /** OCSF Authentication 事件类(Authentication)的 class_uid。 */
    public static final int CLASS_AUTHENTICATION = 3002;

    /** 把 ECS severity 字符串映射为 OCSF severity_id(0=Unknown,1=Info,2=Low,3=Medium,4=High,5=Critical)。 */
    public static int severityId(String severity) {
        if (severity == null) {
            return 0;
        }
        switch (severity.toLowerCase()) {
            case "info": return 1;
            case "low": return 2;
            case "medium": return 3;
            case "high": return 4;
            case "critical": return 5;
            default: return 0;
        }
    }

    /** 在告警 Map 上附加 OCSF 认证视图字段。 */
    public static Map<String, Object> applyAuthView(Map<String, Object> alert, String severity) {
        alert.put("ocsf.class_uid", CLASS_AUTHENTICATION);
        alert.put("ocsf.severity_id", severityId(severity));
        Object ip = alert.get("source.ip");
        if (ip != null) {
            alert.put("ocsf.src_endpoint.ip", ip);
        }
        return alert;
    }
}
