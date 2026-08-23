package com.xscsiem.hsiem_platform.soar;

import com.xscsiem.hsiem_platform.alert.AlertService;
import com.xscsiem.hsiem_platform.auth.ForbiddenException;
import com.xscsiem.hsiem_platform.control.ControlPlaneStore;
import com.xscsiem.hsiem_platform.investigation.CaseService;
import com.xscsiem.hsiem_platform.notify.NotificationService;
import com.xscsiem.hsiem_platform.onboarding.ConflictException;
import com.xscsiem.hsiem_platform.onboarding.NotFoundException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SOAR 顺序执行引擎。只运行白名单内的平台动作；每步先落库再执行，已成功步骤不会重复运行。
 * 审批会暂停执行，请求批准后从快照中的下一步继续。
 */
@Service
public class SoarService {

    private static final Pattern VARIABLE = Pattern.compile("\\$\\{([^}]+)}");
    private final SoarPlaybookRegistry registry;
    private final SoarExecutionStore store;
    private final AlertService alerts;
    private final CaseService cases;
    private final NotificationService notifications;
    private final ControlPlaneStore control;

    public SoarService(SoarPlaybookRegistry registry, SoarExecutionStore store,
                       AlertService alerts, CaseService cases,
                       NotificationService notifications, ControlPlaneStore control) {
        this.registry = registry;
        this.store = store;
        this.alerts = alerts;
        this.cases = cases;
        this.notifications = notifications;
        this.control = control;
    }

    public List<SoarPlaybook> listPlaybooks(String resourceType) {
        return registry.list().stream()
                .filter(SoarPlaybook::isEnabled)
                .filter(p -> resourceType == null || resourceType.isBlank()
                        || p.resourceTypes().contains(resourceType))
                .toList();
    }

    public SoarPlaybook playbook(String id) {
        return registry.get(id);
    }

    public List<SoarPlaybook> reload(String actor) {
        List<SoarPlaybook> result = registry.reload();
        control.audit(actor, "soar.reload_playbooks", "count=" + result.size());
        return result;
    }

    public SoarExecution start(String playbookId, String resourceType, String resourceId, String actor) {
        SoarPlaybook playbook = registry.get(playbookId);
        if (!playbook.isEnabled()) {
            throw new IllegalArgumentException("SOAR Playbook 已停用: " + playbookId);
        }
        if (!playbook.resourceTypes().contains(resourceType)) {
            throw new IllegalArgumentException("Playbook " + playbookId + " 不支持资源类型 " + resourceType);
        }
        if (resourceId == null || resourceId.isBlank()) {
            throw new IllegalArgumentException("resourceId 不能为空");
        }
        String id = "soar-" + UUID.randomUUID();
        Map<String, Object> resource = loadResource(resourceType, resourceId);
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("executionId", id);
        context.put("playbookId", playbook.id());
        context.put("resourceType", resourceType);
        context.put("resourceId", resourceId);
        context.put("resource", resource);
        if ("alert".equals(resourceType)) context.put("alertId", resourceId);
        if ("case".equals(resourceType)) context.put("caseId", resourceId);
        if (!matches(playbook.when(), context)) {
            throw new IllegalArgumentException("资源不满足 Playbook 的 when 条件: " + playbookId);
        }
        Instant now = Instant.now();
        SoarExecution execution = new SoarExecution(id, playbook.id(), playbook.version(),
                resourceType, resourceId, "queued", actor, 0, playbook,
                Map.copyOf(context), null, null, null, null, now, now, null, 0, List.of());
        store.create(execution);
        control.audit(actor, "soar.start", id + ":" + playbook.id() + ":" + resourceType + ":" + resourceId);
        run(id);
        return detail(id);
    }

    public SoarExecution approve(String id, boolean approved, String actor, String role) {
        SoarExecution execution = required(id);
        if (!"waiting_approval".equals(execution.status())) {
            throw new ConflictException("SOAR 执行当前不在等待审批状态: " + id);
        }
        SoarPlaybook.Step step = currentStep(execution);
        if (!"approval".equals(step.action()) || !step.id().equals(execution.approvalStepId())) {
            throw new ConflictException("SOAR 审批步骤与执行状态不一致: " + id);
        }
        String requiredRole = string(SoarPlaybookRegistry.parameters(step).getOrDefault("requiredRole", "analyst"));
        if (!roleAllowed(role, requiredRole)) {
            throw new ForbiddenException("该审批步骤要求角色: " + requiredRole);
        }
        if (!store.resolveApproval(id, step.id(), approved, actor)) {
            throw new ConflictException("SOAR 审批已被其他用户处理: " + id);
        }
        control.audit(actor, approved ? "soar.approve" : "soar.reject", id + ":" + step.id());
        if (approved) run(id);
        return detail(id);
    }

    public SoarExecution retry(String id, String actor) {
        if (!store.prepareRetry(id)) {
            throw new ConflictException("只有 failed 的 SOAR 执行可以重试: " + id);
        }
        control.audit(actor, "soar.retry", id);
        run(id);
        return detail(id);
    }

    public List<SoarExecution> listExecutions(int size) {
        return store.list(size).stream().map(this::withSteps).toList();
    }

    public SoarExecution detail(String id) {
        return withSteps(required(id));
    }

    private void run(String id) {
        if (!store.claimQueued(id)) return;
        SoarExecution execution = required(id);
        List<SoarPlaybook.Step> steps = execution.playbookSnapshot().steps();
        int index = execution.currentStep();
        while (index < steps.size()) {
            SoarPlaybook.Step step = steps.get(index);
            SoarStepExecution previous = store.findStep(id, step.id());
            if (previous != null && List.of("succeeded", "skipped").contains(previous.status())) {
                index++;
                store.advance(id, index);
                continue;
            }
            Map<String, Object> input = resolveMap(SoarPlaybookRegistry.parameters(step), execution.context());
            store.startStep(id, index, step, input);
            if (!matches(step.when(), execution.context())) {
                store.finishStep(id, step.id(), "skipped", Map.of("reason", "condition_not_matched"), null);
                index++;
                store.advance(id, index);
                continue;
            }
            if ("approval".equals(step.action())) {
                String message = string(input.getOrDefault("message", step.name()));
                store.waitForApproval(id, index, step.id(), message);
                control.audit(execution.actor(), "soar.waiting_approval", id + ":" + step.id());
                return;
            }
            try {
                Map<String, Object> output = executeAction(step.action(), input, execution);
                store.finishStep(id, step.id(), "succeeded", output, null);
                index++;
                store.advance(id, index);
            } catch (Exception e) {
                String error = safeError(e);
                store.finishStep(id, step.id(), "failed", Map.of(), error);
                store.finishExecution(id, "failed", error);
                control.audit(execution.actor(), "soar.failed", id + ":" + step.id() + ":" + error);
                return;
            }
        }
        store.finishExecution(id, "succeeded", null);
        control.audit(execution.actor(), "soar.succeeded", id);
    }

    private Map<String, Object> executeAction(String action, Map<String, Object> input,
                                              SoarExecution execution) {
        String actor = execution.actor();
        return switch (action) {
            case "alert.set_status" -> {
                String alertId = reference(input, "alertId", execution.context(), "alertId");
                String status = requiredString(input, "status");
                Map<String, Object> result = alerts.update(alertId, status, null, actor);
                yield summary("alertId", alertId, "status", result.get("alert.status"));
            }
            case "alert.set_verdict" -> {
                String alertId = reference(input, "alertId", execution.context(), "alertId");
                String verdict = requiredString(input, "verdict");
                Map<String, Object> result = alerts.update(alertId, null, verdict, actor);
                yield summary("alertId", alertId, "verdict", result.get("alert.analyst_verdict"));
            }
            case "case.set_status" -> {
                String caseId = reference(input, "caseId", execution.context(), "caseId");
                String status = requiredString(input, "status");
                String verdict = nullableString(input.get("verdict"));
                Map<String, Object> result = cases.updateStatus(caseId, status, verdict, actor);
                yield summary("caseId", caseId, "status", result.get("case.status"));
            }
            case "case.add_alert" -> {
                String caseId = reference(input, "caseId", execution.context(), "caseId");
                String alertId = reference(input, "alertId", execution.context(), "alertId");
                Map<String, Object> result = cases.addAlerts(caseId, List.of(alertId), actor);
                yield summary("caseId", caseId, "alertCount", list(result.get("alert_ids")).size());
            }
            case "case.add_evidence" -> addEvidence(input, execution);
            case "notification.create" -> {
                String type = string(input.getOrDefault("type", "soar"));
                String target = string(input.getOrDefault("target", execution.resourceType() + ":" + execution.resourceId()));
                String message = requiredString(input, "message");
                notifications.notify(type, target, message);
                yield summary("type", type, "target", target);
            }
            default -> throw new IllegalArgumentException("未允许的 SOAR action: " + action);
        };
    }

    private Map<String, Object> addEvidence(Map<String, Object> input, SoarExecution execution) {
        String caseId = reference(input, "caseId", execution.context(), "caseId");
        Map<String, Object> current = cases.detail(caseId);
        List<Map<String, Object>> evidence = new ArrayList<>(mapList(current.get("evidence")));
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", string(input.getOrDefault("type", "soar")));
        item.put("title", requiredString(input, "title"));
        putIfPresent(item, "uri", input.get("uri"));
        putIfPresent(item, "note", input.get("note"));
        item.put("createdBy", execution.actor());
        item.put("createdAt", Instant.now().toString());
        evidence.add(item);
        cases.updateMetadata(caseId, nullableString(current.get("case.owner")), evidence, execution.actor());
        return summary("caseId", caseId, "evidenceCount", evidence.size());
    }

    private Map<String, Object> loadResource(String resourceType, String resourceId) {
        Map<String, Object> resource = switch (resourceType) {
            case "alert" -> alerts.detail(resourceId);
            case "case" -> cases.detail(resourceId);
            default -> throw new IllegalArgumentException("resourceType 仅支持 alert/case");
        };
        if (resource == null) throw new NotFoundException(resourceType + " 资源不存在: " + resourceId);
        return resource;
    }

    static boolean matches(SoarPlaybook.Condition condition, Map<String, Object> context) {
        if (condition == null) return true;
        Object actual = lookup(context, condition.field());
        Object expected = condition.value();
        return switch (condition.operator()) {
            case "exists" -> expected instanceof Boolean b ? (actual != null) == b : actual != null;
            case "eq" -> Objects.equals(normalize(actual), normalize(expected));
            case "ne" -> !Objects.equals(normalize(actual), normalize(expected));
            case "gt" -> compare(actual, expected) > 0;
            case "gte" -> compare(actual, expected) >= 0;
            case "lt" -> compare(actual, expected) < 0;
            case "lte" -> compare(actual, expected) <= 0;
            case "contains" -> actual instanceof Collection<?> c
                    ? c.stream().anyMatch(v -> Objects.equals(normalize(v), normalize(expected)))
                    : actual != null && String.valueOf(actual).contains(String.valueOf(expected));
            default -> false;
        };
    }

    static Map<String, Object> resolveMap(Map<String, Object> source, Map<String, Object> context) {
        Map<String, Object> out = new LinkedHashMap<>();
        source.forEach((key, value) -> out.put(key, resolve(value, context)));
        return out;
    }

    private static Object resolve(Object value, Map<String, Object> context) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), resolve(v, context)));
            return out;
        }
        if (value instanceof List<?> values) return values.stream().map(v -> resolve(v, context)).toList();
        if (!(value instanceof String text)) return value;
        Matcher matcher = VARIABLE.matcher(text);
        if (matcher.matches()) return lookup(context, matcher.group(1));
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            Object replacement = lookup(context, matcher.group(1));
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement == null ? "" : String.valueOf(replacement)));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    @SuppressWarnings("unchecked")
    static Object lookup(Map<String, Object> context, String path) {
        if (context == null || path == null) return null;
        if (context.containsKey(path)) return context.get(path);
        Object current = context;
        String[] parts = path.split("\\.");
        for (int i = 0; i < parts.length; i++) {
            if (!(current instanceof Map<?, ?> map)) return null;
            String remaining = String.join(".", java.util.Arrays.copyOfRange(parts, i, parts.length));
            if (map.containsKey(remaining)) return map.get(remaining);
            current = ((Map<String, Object>) map).get(parts[i]);
        }
        return current;
    }

    private SoarExecution required(String id) {
        SoarExecution execution = store.find(id);
        if (execution == null) throw new NotFoundException("SOAR 执行不存在: " + id);
        return execution;
    }

    private SoarExecution withSteps(SoarExecution execution) {
        return execution.withSteps(store.listSteps(execution.id()));
    }

    private static SoarPlaybook.Step currentStep(SoarExecution execution) {
        if (execution.currentStep() < 0 || execution.currentStep() >= execution.playbookSnapshot().steps().size()) {
            throw new ConflictException("SOAR 当前步骤越界: " + execution.id());
        }
        return execution.playbookSnapshot().steps().get(execution.currentStep());
    }

    private static boolean roleAllowed(String actual, String required) {
        if ("admin".equals(actual)) return true;
        return Objects.equals(actual, required);
    }

    private static String reference(Map<String, Object> input, String inputKey,
                                    Map<String, Object> context, String contextKey) {
        Object value = input.get(inputKey);
        if (value == null) value = context.get(contextKey);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalArgumentException("SOAR action 缺少 " + inputKey);
        }
        return String.valueOf(value);
    }

    private static String requiredString(Map<String, Object> input, String key) {
        String value = nullableString(input.get(key));
        if (value == null || value.isBlank()) throw new IllegalArgumentException("SOAR action 缺少 " + key);
        return value;
    }

    private static Map<String, Object> summary(String firstKey, Object firstValue,
                                               String secondKey, Object secondValue) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(firstKey, firstValue);
        out.put(secondKey, secondValue);
        return out;
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) target.put(key, value);
    }

    private static String safeError(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) message = e.getClass().getSimpleName();
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }

    private static Object normalize(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        return value == null ? null : String.valueOf(value);
    }

    private static int compare(Object left, Object right) {
        try {
            return Double.compare(Double.parseDouble(String.valueOf(left)), Double.parseDouble(String.valueOf(right)));
        } catch (Exception e) {
            return String.valueOf(left).compareTo(String.valueOf(right));
        }
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String nullableString(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> result ? result : List.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mapList(Object value) {
        if (!(value instanceof List<?> values)) return List.of();
        return values.stream().filter(Map.class::isInstance)
                .map(item -> (Map<String, Object>) item).toList();
    }
}
