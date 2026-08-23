package com.xscsiem.hsiem_platform.soar;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/** Git/YAML 中的 SOAR 定义。V2 使用节点和条件边；steps 仅兼容 V1 快照。 */
public record SoarPlaybook(
        String formatVersion,
        String id,
        String name,
        String description,
        String version,
        Boolean enabled,
        List<String> resourceTypes,
        Condition when,
        String entrypoint,
        Defaults defaults,
        List<Trigger> triggers,
        List<Node> nodes,
        List<Step> steps) {

    /** V1 Java 调用兼容构造器，旧线性 Playbook 仍由 SoarGraph 编译。 */
    public SoarPlaybook(String id, String name, String description, String version,
                        Boolean enabled, List<String> resourceTypes, Condition when,
                        List<Step> steps) {
        this("1", id, name, description, version, enabled, resourceTypes, when,
                null, null, null, null, steps);
    }

    public boolean isEnabled() {
        return enabled == null || enabled;
    }

    @JsonIgnore
    public boolean isGraph() {
        return nodes != null && !nodes.isEmpty();
    }

    public record Defaults(Integer timeoutSeconds, RetryPolicy retry) {
    }

    public record RetryPolicy(Integer maxAttempts, Integer delaySeconds, Double backoffMultiplier) {
    }

    public record Trigger(String id, String type, Boolean enabled, Condition when, String dedupWindow) {
        public boolean isEnabled() {
            return Boolean.TRUE.equals(enabled);
        }
    }

    public record Node(
            String id,
            String name,
            String type,
            String action,
            @JsonProperty("with") Map<String, Object> parameters,
            Condition when,
            Boolean exclusive,
            String join,
            Integer delaySeconds,
            Integer timeoutSeconds,
            RetryPolicy retry,
            String result,
            List<Transition> transitions) {
    }

    public record Transition(String target, @JsonProperty("on") String event, Condition when) {
    }

    /** V1 兼容结构，由 SoarGraph 编译成线性节点图。 */
    public record Step(
            String id,
            String name,
            String action,
            @JsonProperty("with") Map<String, Object> parameters,
            Condition when) {
    }

    /** 支持叶子表达式以及 all/any/not 组合条件。 */
    public record Condition(String field, String operator, Object value,
                            List<Condition> all, List<Condition> any, Condition not) {

        public Condition(String field, String operator, Object value) {
            this(field, operator, value, null, null, null);
        }
    }
}
