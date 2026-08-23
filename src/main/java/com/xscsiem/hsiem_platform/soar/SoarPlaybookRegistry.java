package com.xscsiem.hsiem_platform.soar;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.xscsiem.hsiem_platform.onboarding.NotFoundException;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 从受版本控制的 YAML 目录加载并校验 Playbook。 */
@Component
public class SoarPlaybookRegistry {

    public static final Set<String> ALLOWED_ACTIONS = Set.of(
            "approval",
            "alert.set_status",
            "alert.set_verdict",
            "case.set_status",
            "case.add_alert",
            "case.add_evidence",
            "notification.create",
            "context.set",
            "connector.call");
    private static final Set<String> NODE_TYPES = Set.of(
            "action", "decision", "approval", "delay", "subplaybook", "loop", "map", "end");
    private static final Set<String> EVENTS = Set.of(
            "success", "failure", "approved", "rejected", "complete", "always");
    private static final Set<String> OPERATORS = Set.of(
            "eq", "ne", "gt", "gte", "lt", "lte", "exists", "contains", "matches");
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{2,127}");

    private final Path directory;
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory()).findAndRegisterModules();
    private volatile Map<String, SoarPlaybook> playbooks = Map.of();

    public SoarPlaybookRegistry(@Value("${app.soar.playbooks-dir:infra/soar/playbooks}") String directory) {
        this.directory = Path.of(directory).toAbsolutePath().normalize();
    }

    @PostConstruct
    public void initialize() {
        reload();
    }

    public List<SoarPlaybook> list() {
        return new ArrayList<>(playbooks.values());
    }

    public SoarPlaybook get(String id) {
        SoarPlaybook playbook = playbooks.get(id);
        if (playbook == null) {
            throw new NotFoundException("SOAR Playbook 不存在: " + id);
        }
        return playbook;
    }

    public synchronized List<SoarPlaybook> reload() {
        if (!Files.isDirectory(directory)) {
            throw new IllegalStateException("SOAR Playbook 目录不存在: " + directory);
        }
        Map<String, SoarPlaybook> loaded = new LinkedHashMap<>();
        try (var files = Files.list(directory)) {
            for (Path path : files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".yaml")
                            || p.getFileName().toString().endsWith(".yml"))
                    .sorted().toList()) {
                SoarPlaybook playbook = yaml.readValue(path.toFile(), SoarPlaybook.class);
                validate(playbook, path);
                if (loaded.putIfAbsent(playbook.id(), playbook) != null) {
                    throw new IllegalArgumentException("SOAR Playbook ID 重复: " + playbook.id());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("读取 SOAR Playbook 失败: " + e.getMessage(), e);
        }
        playbooks = Collections.unmodifiableMap(new LinkedHashMap<>(loaded));
        return list();
    }

    static void validate(SoarPlaybook playbook, Path source) {
        String label = source == null ? "playbook" : source.toString();
        if (playbook == null || playbook.id() == null || !ID.matcher(playbook.id()).matches()) {
            throw new IllegalArgumentException(label + " 的 id 必须为 3-128 位小写字母、数字或连字符");
        }
        if (blank(playbook.name()) || blank(playbook.version())) {
            throw new IllegalArgumentException(playbook.id() + " 缺少 name/version");
        }
        if (playbook.resourceTypes() == null || playbook.resourceTypes().isEmpty()
                || playbook.resourceTypes().stream().anyMatch(type -> !Set.of("alert", "case").contains(type))) {
            throw new IllegalArgumentException(playbook.id() + " 的 resourceTypes 仅支持 alert/case");
        }
        validateCondition(playbook.id(), playbook.when());
        if (playbook.isGraph()) validateGraph(playbook);
        else validateLegacy(playbook);
        validateDefaults(playbook);
        validateTriggers(playbook);
    }

    private static void validateLegacy(SoarPlaybook playbook) {
        if (playbook.steps() == null || playbook.steps().isEmpty() || playbook.steps().size() > 50) {
            throw new IllegalArgumentException(playbook.id() + " 的 V1 步骤数需在 1-50 之间");
        }
        Set<String> stepIds = new HashSet<>();
        for (SoarPlaybook.Step step : playbook.steps()) {
            if (step == null || step.id() == null || !ID.matcher(step.id()).matches()) {
                throw new IllegalArgumentException(playbook.id() + " 存在非法 step.id");
            }
            if (!stepIds.add(step.id())) {
                throw new IllegalArgumentException(playbook.id() + " 的 step.id 重复: " + step.id());
            }
            if (blank(step.name()) || !ALLOWED_ACTIONS.contains(step.action())) {
                throw new IllegalArgumentException(playbook.id() + "/" + step.id() + " 的 name/action 非法");
            }
            validateActionParameters(playbook.id() + "/" + step.id(), step.action(), parameters(step));
            validateCondition(playbook.id() + "/" + step.id(), step.when());
            if ("approval".equals(step.action())) {
                String role = string(parameters(step).getOrDefault("requiredRole", "analyst"));
                if (!Set.of("admin", "analyst").contains(role)) {
                    throw new IllegalArgumentException(playbook.id() + "/" + step.id() + " 的审批角色仅支持 admin/analyst");
                }
            }
        }
    }

    private static void validateGraph(SoarPlaybook playbook) {
        if (!"2".equals(playbook.formatVersion())) {
            throw new IllegalArgumentException(playbook.id() + " 的图 Playbook 必须声明 formatVersion: \"2\"");
        }
        if (playbook.nodes().size() > 100 || blank(playbook.entrypoint())) {
            throw new IllegalArgumentException(playbook.id() + " 的节点数需在 1-100 且必须声明 entrypoint");
        }
        Set<String> ids = new HashSet<>();
        for (SoarPlaybook.Node node : playbook.nodes()) {
            if (node == null || node.id() == null || !ID.matcher(node.id()).matches()
                    || !ids.add(node.id())) {
                throw new IllegalArgumentException(playbook.id() + " 存在非法或重复 node.id");
            }
            if (blank(node.name()) || !NODE_TYPES.contains(node.type())) {
                throw new IllegalArgumentException(playbook.id() + "/" + node.id() + " 的 name/type 非法");
            }
            if ("action".equals(node.type()) && !ALLOWED_ACTIONS.contains(node.action())) {
                throw new IllegalArgumentException(playbook.id() + "/" + node.id() + " 的 action 未进入白名单");
            }
            if ("action".equals(node.type())) {
                validateActionParameters(playbook.id() + "/" + node.id(), node.action(),
                        node.parameters() == null ? Map.of() : node.parameters());
            }
            if ("subplaybook".equals(node.type())) {
                require(playbook.id() + "/" + node.id(), SoarGraph.parameters(node), "playbookId");
            }
            if ("map".equals(node.type())) validateMap(playbook.id(), node);
            if ("loop".equals(node.type())) validateLoop(playbook.id(), node);
            if ("approval".equals(node.type())) validateApproval(playbook.id(), node.id(), node.parameters());
            if ("delay".equals(node.type()) && bounded(node.delaySeconds(), 1, 86400) == null) {
                throw new IllegalArgumentException(playbook.id() + "/" + node.id() + " 的 delaySeconds 需在 1-86400");
            }
            if (node.timeoutSeconds() != null && bounded(node.timeoutSeconds(), 1, 300) == null) {
                throw new IllegalArgumentException(playbook.id() + "/" + node.id() + " 的 timeoutSeconds 需在 1-300");
            }
            validateRetry(playbook.id() + "/" + node.id(), node.retry());
            validateCondition(playbook.id() + "/" + node.id(), node.when());
        }
        if (!ids.contains(playbook.entrypoint())) {
            throw new IllegalArgumentException(playbook.id() + " 的 entrypoint 不存在");
        }
        for (SoarPlaybook.Node node : playbook.nodes()) {
            for (SoarPlaybook.Transition transition : SoarGraph.transitions(node)) {
                String event = blank(transition.event()) ? "success" : transition.event();
                if (!ids.contains(transition.target()) || !EVENTS.contains(event)) {
                    throw new IllegalArgumentException(playbook.id() + "/" + node.id() + " 存在非法 transition");
                }
                validateCondition(playbook.id() + "/" + node.id() + "->" + transition.target(), transition.when());
            }
        }
        Set<String> reachable = new HashSet<>();
        visit(playbook.entrypoint(), SoarGraph.compile(playbook), reachable);
        if (reachable.size() != ids.size()) {
            Set<String> unreachable = new HashSet<>(ids);
            unreachable.removeAll(reachable);
            throw new IllegalArgumentException(playbook.id() + " 存在不可达节点: " + unreachable);
        }
    }

    private static void validateDefaults(SoarPlaybook playbook) {
        if (playbook.defaults() == null) return;
        if (playbook.defaults().timeoutSeconds() != null
                && bounded(playbook.defaults().timeoutSeconds(), 1, 300) == null) {
            throw new IllegalArgumentException(playbook.id() + " 默认 timeoutSeconds 需在 1-300");
        }
        validateRetry(playbook.id() + "/defaults", playbook.defaults().retry());
    }

    private static void validateTriggers(SoarPlaybook playbook) {
        if (playbook.triggers() == null) return;
        Set<String> ids = new HashSet<>();
        for (SoarPlaybook.Trigger trigger : playbook.triggers()) {
            if (trigger == null || trigger.id() == null || !ID.matcher(trigger.id()).matches()
                    || !ids.add(trigger.id()) || !Set.of("alert", "case").contains(trigger.type())) {
                throw new IllegalArgumentException(playbook.id() + " 存在非法或重复 trigger");
            }
            if (!playbook.resourceTypes().contains(trigger.type())) {
                throw new IllegalArgumentException(playbook.id() + "/" + trigger.id() + " 触发资源类型不兼容");
            }
            validateCondition(playbook.id() + "/" + trigger.id(), trigger.when());
            if (!blank(trigger.dedupWindow())) {
                try {
                    if (java.time.Duration.parse(trigger.dedupWindow()).isNegative()) throw new Exception();
                } catch (Exception e) {
                    throw new IllegalArgumentException(playbook.id() + "/" + trigger.id() + " dedupWindow 非法");
                }
            }
        }
    }

    private static void validateRetry(String owner, SoarPlaybook.RetryPolicy retry) {
        if (retry == null) return;
        if (retry.maxAttempts() != null && bounded(retry.maxAttempts(), 1, 10) == null
                || retry.delaySeconds() != null && bounded(retry.delaySeconds(), 0, 3600) == null
                || retry.backoffMultiplier() != null
                && (retry.backoffMultiplier() < 1.0 || retry.backoffMultiplier() > 10.0)) {
            throw new IllegalArgumentException(owner + " 的 retry 策略非法");
        }
    }

    private static void validateApproval(String playbookId, String nodeId, Map<String, Object> parameters) {
        String role = string((parameters == null ? Map.of() : parameters)
                .getOrDefault("requiredRole", "analyst"));
        if (!Set.of("admin", "analyst").contains(role)) {
            throw new IllegalArgumentException(playbookId + "/" + nodeId + " 的审批角色仅支持 admin/analyst");
        }
    }

    private static void validateMap(String playbookId, SoarPlaybook.Node node) {
        Map<String, Object> parameters = SoarGraph.parameters(node);
        require(playbookId + "/" + node.id(), parameters, "items");
        String action = string(parameters.get("action"));
        if (!ALLOWED_ACTIONS.contains(action) || "approval".equals(action)) {
            throw new IllegalArgumentException(playbookId + "/" + node.id() + " 的 map action 非法");
        }
        int maxItems = integer(parameters.getOrDefault("maxItems", 100), -1);
        int concurrency = integer(parameters.getOrDefault("concurrency", 4), -1);
        if (maxItems < 1 || maxItems > 1000 || concurrency < 1 || concurrency > 32) {
            throw new IllegalArgumentException(playbookId + "/" + node.id() + " 的 map 上限非法");
        }
    }

    private static void validateLoop(String playbookId, SoarPlaybook.Node node) {
        int maxIterations = integer(SoarGraph.parameters(node).getOrDefault("maxIterations", 10), -1);
        if (maxIterations < 1 || maxIterations > 100) {
            throw new IllegalArgumentException(playbookId + "/" + node.id() + " 的 loop.maxIterations 需在 1-100");
        }
        boolean hasComplete = SoarGraph.transitions(node).stream()
                .anyMatch(edge -> "complete".equals(edge.event()));
        if (!hasComplete) throw new IllegalArgumentException(playbookId + "/" + node.id() + " 缺少 complete 路由");
    }

    /** 动作输入在定义加载期失败，而不是等到事件处置中途才暴露拼写或结构错误。 */
    private static void validateActionParameters(String owner, String action,
                                                 Map<String, Object> parameters) {
        switch (action) {
            case "alert.set_status", "case.set_status" -> require(owner, parameters, "status");
            case "alert.set_verdict" -> require(owner, parameters, "verdict");
            case "case.add_alert" -> require(owner, parameters, "alertId");
            case "case.add_evidence" -> require(owner, parameters, "title");
            case "notification.create" -> require(owner, parameters, "message");
            case "context.set" -> {
                if (!(parameters.get("values") instanceof Map<?, ?> values) || values.isEmpty()) {
                    throw new IllegalArgumentException(owner + " 的 context.set 必须声明非空 with.values");
                }
            }
            case "connector.call" -> {
                require(owner, parameters, "connector");
                require(owner, parameters, "operation");
                Object arguments = parameters.get("arguments");
                if (arguments != null && !(arguments instanceof Map<?, ?>)) {
                    throw new IllegalArgumentException(owner + " 的 connector.call.with.arguments 必须是对象");
                }
            }
            default -> {
                // 无必填参数的动作由 Runner 执行类型化校验。
            }
        }
    }

    private static void require(String owner, Map<String, Object> parameters, String key) {
        Object value = parameters.get(key);
        if (value == null || value instanceof String text && text.isBlank()) {
            throw new IllegalArgumentException(owner + " 缺少 with." + key);
        }
    }

    private static void visit(String id, SoarGraph graph, Set<String> visited) {
        if (!visited.add(id)) return;
        SoarPlaybook.Node node = graph.node(id);
        if (node != null) SoarGraph.transitions(node).forEach(edge -> visit(edge.target(), graph, visited));
    }

    static Map<String, Object> parameters(SoarPlaybook.Step step) {
        return step.parameters() == null ? Map.of() : step.parameters();
    }

    private static void validateCondition(String owner, SoarPlaybook.Condition condition) {
        if (condition == null) return;
        boolean leaf = !blank(condition.field()) && OPERATORS.contains(condition.operator());
        boolean group = condition.all() != null && !condition.all().isEmpty()
                || condition.any() != null && !condition.any().isEmpty() || condition.not() != null;
        if (!leaf && !group) throw new IllegalArgumentException(owner + " 的 when 条件非法");
        if (condition.all() != null) condition.all().forEach(item -> validateCondition(owner, item));
        if (condition.any() != null) condition.any().forEach(item -> validateCondition(owner, item));
        validateCondition(owner, condition.not());
    }

    private static Integer bounded(Integer value, int min, int max) {
        return value != null && value >= min && value <= max ? value : null;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static int integer(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return fallback;
        }
    }
}
