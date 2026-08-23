package com.xscsiem.hsiem_platform.soar;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/** Git/YAML 中的 SOAR Playbook；执行时会把完整定义快照写入 PostgreSQL。 */
public record SoarPlaybook(
        String id,
        String name,
        String description,
        String version,
        Boolean enabled,
        List<String> resourceTypes,
        Condition when,
        List<Step> steps) {

    public boolean isEnabled() {
        return enabled == null || enabled;
    }

    public record Step(
            String id,
            String name,
            String action,
            @JsonProperty("with") Map<String, Object> parameters,
            Condition when) {
    }

    public record Condition(String field, String operator, Object value) {
    }
}
