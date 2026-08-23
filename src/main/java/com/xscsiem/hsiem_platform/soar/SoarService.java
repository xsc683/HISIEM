package com.xscsiem.hsiem_platform.soar;

import com.xscsiem.hsiem_platform.alert.AlertService;
import com.xscsiem.hsiem_platform.auth.ForbiddenException;
import com.xscsiem.hsiem_platform.control.ControlPlaneStore;
import com.xscsiem.hsiem_platform.investigation.CaseService;
import com.xscsiem.hsiem_platform.tenant.TenantContext;
import com.xscsiem.hsiem_platform.onboarding.ConflictException;
import com.xscsiem.hsiem_platform.onboarding.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** SOAR 控制面：创建、审批、重试、暂停、取消；执行推进只由 SoarWorker 完成。 */
@Service
public class SoarService {

    private final SoarPlaybookRegistry registry;
    private final SoarPlaybookCatalog catalog;
    private final SoarExecutionStore store;
    private final AlertService alerts;
    private final CaseService cases;
    private final ControlPlaneStore control;

    @Autowired
    public SoarService(SoarPlaybookRegistry registry, SoarPlaybookCatalog catalog,
                       SoarExecutionStore store,
                       AlertService alerts, CaseService cases, ControlPlaneStore control) {
        this.registry = registry;
        this.catalog = catalog;
        this.store = store;
        this.alerts = alerts;
        this.cases = cases;
        this.control = control;
    }

    /** 轻量单元测试兼容构造器。 */
    public SoarService(SoarPlaybookRegistry registry, SoarExecutionStore store,
                       AlertService alerts, CaseService cases, ControlPlaneStore control) {
        this.registry = registry;
        this.catalog = null;
        this.store = store;
        this.alerts = alerts;
        this.cases = cases;
        this.control = control;
    }

    public List<SoarPlaybook> listPlaybooks(String resourceType) {
        List<SoarPlaybook> source = catalog == null ? registry.list()
                : catalog.listPublished(TenantContext.id());
        return source.stream().filter(SoarPlaybook::isEnabled)
                .filter(playbook -> resourceType == null || resourceType.isBlank()
                        || playbook.resourceTypes().contains(resourceType)).toList();
    }

    public SoarPlaybook playbook(String id) {
        return catalog == null ? registry.get(id) : catalog.resolve(TenantContext.id(), id, "read");
    }

    public List<SoarPlaybook> reload(String actor) {
        if (catalog == null) {
            List<SoarPlaybook> result = registry.reload();
            control.audit(actor, "soar.reload_playbooks", "count=" + result.size());
            return result;
        }
        int imported = catalog.importGitAsDraft(TenantContext.id(), actor).size();
        control.audit(actor, "soar.import_playbook_drafts", "count=" + imported);
        return listPlaybooks(null);
    }

    @Transactional
    public SoarExecution start(String playbookId, String resourceType, String resourceId, String actor) {
        String tenantId = TenantContext.id();
        SoarPlaybook selected = catalog == null ? registry.get(playbookId)
                : catalog.resolve(tenantId, playbookId, resourceId);
        return startInternal(tenantId, selected,
                resourceType, resourceId, actor, "manual", null, null, null, null);
    }

    @Transactional
    public SoarExecution startTriggered(SoarPlaybook playbook, SoarPlaybook.Trigger trigger,
                                        String resourceType, String resourceId,
                                        Map<String, Object> resource) {
        String bucket = "forever";
        if (trigger.dedupWindow() != null && !trigger.dedupWindow().isBlank()) {
            long seconds = Math.max(1, Duration.parse(trigger.dedupWindow()).toSeconds());
            bucket = String.valueOf(Instant.now().getEpochSecond() / seconds);
        }
        String dedup = TenantContext.id() + ":" + playbook.id() + ":" + playbook.version() + ":" + trigger.id()
                + ":" + resourceType + ":" + resourceId + ":" + bucket;
        return startInternal(TenantContext.id(), playbook, resourceType, resourceId,
                "soar-trigger:" + trigger.id(), trigger.type(), dedup, resource, null, null);
    }

    SoarExecution startInternal(String tenantId, SoarPlaybook playbook,
                                        String resourceType, String resourceId,
                                        String actor, String triggerType, String dedupKey,
                                        Map<String, Object> suppliedResource,
                                        String parentExecutionId, String parentNodeId) {
        if (!playbook.isEnabled()) throw new IllegalArgumentException("SOAR Playbook 已停用: " + playbook.id());
        if (!playbook.resourceTypes().contains(resourceType)) {
            throw new IllegalArgumentException("Playbook " + playbook.id() + " 不支持资源类型 " + resourceType);
        }
        if (resourceId == null || resourceId.isBlank()) throw new IllegalArgumentException("resourceId 不能为空");
        Map<String, Object> resource = suppliedResource == null
                ? loadResource(resourceType, resourceId) : suppliedResource;
        String id = dedupKey == null ? "soar-" + UUID.randomUUID()
                : "soar-" + UUID.nameUUIDFromBytes(dedupKey.getBytes(StandardCharsets.UTF_8));
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("executionId", id);
        context.put("playbookId", playbook.id());
        context.put("tenantId", tenantId);
        context.put("resourceType", resourceType);
        context.put("resourceId", resourceId);
        context.put("resource", resource);
        context.put("nodes", new LinkedHashMap<>());
        context.put("variables", new LinkedHashMap<>());
        if ("alert".equals(resourceType)) context.put("alertId", resourceId);
        if ("case".equals(resourceType)) context.put("caseId", resourceId);
        if (!SoarExpression.matches(playbook.when(), context)) {
            throw new IllegalArgumentException("资源不满足 Playbook 的 when 条件: " + playbook.id());
        }
        SoarGraph graph = SoarGraph.compile(playbook);
        Instant now = Instant.now();
        SoarExecution execution = new SoarExecution(id, playbook.id(), playbook.version(),
                resourceType, resourceId, "queued", actor, 0, graph.entrypoint(),
                List.of(graph.entrypoint()), playbook, context, triggerType, dedupKey,
                null, null, null, null, now, null, null, false, false, 0,
                now, now, null, 0, tenantId, parentExecutionId, parentNodeId, List.of());
        if (!store.create(execution)) {
            SoarExecution existing = store.find(id);
            if (existing != null) return withSteps(existing);
            throw new ConflictException("SOAR 自动触发已去重: " + dedupKey);
        }
        store.appendEvent(id, "execution.created", null, actor, Map.of(
                "triggerType", triggerType, "playbook", playbook.id(),
                "resourceType", resourceType, "resourceId", resourceId));
        control.audit(actor, "soar.start", id + ":" + playbook.id() + ":" + resourceType + ":" + resourceId);
        return detail(id);
    }

    @Transactional
    public SoarExecution approve(String id, boolean approved, String actor, String role) {
        SoarExecution execution = required(id);
        if (!"waiting_approval".equals(execution.status())) {
            throw new ConflictException("SOAR 执行当前不在等待审批状态: " + id);
        }
        SoarGraph graph = SoarGraph.compile(execution.playbookSnapshot());
        SoarPlaybook.Node node = graph.node(execution.approvalStepId());
        if (node == null || !"approval".equals(node.type())) {
            throw new ConflictException("SOAR 审批节点与执行状态不一致: " + id);
        }
        String requiredRole = string(SoarGraph.parameters(node).getOrDefault("requiredRole", "analyst"));
        if (!roleAllowed(role, requiredRole)) throw new ForbiddenException("该审批步骤要求角色: " + requiredRole);

        Map<String, Object> context = mutableContext(execution.context());
        putApprovalResult(context, node.id(), approved, actor);
        LinkedHashSet<String> frontier = new LinkedHashSet<>(execution.frontier());
        frontier.remove(node.id());
        frontier.addAll(SoarEngine.transitionTargets(node, approved ? "approved" : "rejected", context));
        boolean continueExecution = !frontier.isEmpty();
        if (!store.resolveApproval(id, node.id(), approved, actor, List.copyOf(frontier),
                context, continueExecution)) {
            throw new ConflictException("SOAR 审批已被其他用户处理: " + id);
        }
        String event = approved ? "approval.approved" : "approval.rejected";
        store.appendEvent(id, event, node.id(), actor, Map.of("continued", continueExecution));
        control.audit(actor, approved ? "soar.approve" : "soar.reject", id + ":" + node.id());
        return detail(id);
    }

    @Transactional
    public SoarExecution retry(String id, String actor) {
        if (!store.prepareRetry(id)) {
            throw new ConflictException("只有包含失败动作节点的 failed SOAR 执行可以重试: " + id);
        }
        store.appendEvent(id, "execution.retry_requested", null, actor, Map.of());
        control.audit(actor, "soar.retry", id);
        return detail(id);
    }

    @Transactional
    public SoarExecution cancel(String id, String actor) {
        if (!store.requestCancel(id)) throw new ConflictException("当前状态不能取消 SOAR 执行: " + id);
        store.appendEvent(id, "execution.cancel_requested", null, actor, Map.of());
        control.audit(actor, "soar.cancel", id);
        return detail(id);
    }

    @Transactional
    public SoarExecution pause(String id, String actor) {
        if (!store.requestPause(id)) throw new ConflictException("只有 queued/running 执行可以暂停: " + id);
        store.appendEvent(id, "execution.pause_requested", null, actor, Map.of());
        control.audit(actor, "soar.pause", id);
        return detail(id);
    }

    @Transactional
    public SoarExecution resume(String id, String actor) {
        if (!store.resume(id)) throw new ConflictException("只有 paused 执行可以恢复: " + id);
        store.appendEvent(id, "execution.resumed", null, actor, Map.of());
        control.audit(actor, "soar.resume", id);
        return detail(id);
    }

    public List<SoarExecution> listExecutions(int size) {
        return store.list(TenantContext.id(), size).stream().map(this::withSteps).toList();
    }

    public SoarExecution detail(String id) {
        return withSteps(required(id));
    }

    public List<SoarExecutionEvent> events(String id) {
        required(id);
        return store.listEvents(id);
    }

    public List<Map<String, Object>> automationRules() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (SoarPlaybook playbook : listPlaybooks(null)) {
            for (SoarPlaybook.Trigger trigger : playbook.triggers() == null
                    ? List.<SoarPlaybook.Trigger>of() : playbook.triggers()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", playbook.id() + ":" + trigger.id());
                row.put("playbookId", playbook.id());
                row.put("playbookName", playbook.name());
                row.put("trigger", trigger);
                row.put("active", playbook.isEnabled() && trigger.isEnabled());
                out.add(row);
            }
        }
        return out;
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

    private SoarExecution required(String id) {
        SoarExecution execution = store.find(id);
        if (execution == null) throw new NotFoundException("SOAR 执行不存在: " + id);
        if (!TenantContext.id().equals(execution.tenantId())) {
            throw new NotFoundException("SOAR 执行不存在: " + id);
        }
        return execution;
    }

    private SoarExecution withSteps(SoarExecution execution) {
        return execution.withSteps(store.listSteps(execution.id()));
    }

    @SuppressWarnings("unchecked")
    private static void putApprovalResult(Map<String, Object> context, String nodeId,
                                          boolean approved, String actor) {
        Map<String, Object> nodes = context.get("nodes") instanceof Map<?, ?> current
                ? new LinkedHashMap<>((Map<String, Object>) current) : new LinkedHashMap<>();
        nodes.put(nodeId, Map.of("status", approved ? "succeeded" : "rejected",
                "attempt", 1, "output", Map.of("approved", approved, "actor", actor)));
        context.put("nodes", nodes);
    }

    private static Map<String, Object> mutableContext(Map<String, Object> source) {
        return source == null ? new LinkedHashMap<>() : new LinkedHashMap<>(source);
    }

    private static boolean roleAllowed(String actual, String required) {
        return "admin".equals(actual) || Objects.equals(actual, required);
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    // 兼容既有表达式单元测试。
    static boolean matches(SoarPlaybook.Condition condition, Map<String, Object> context) {
        return SoarExpression.matches(condition, context);
    }

    static Map<String, Object> resolveMap(Map<String, Object> source, Map<String, Object> context) {
        return SoarExpression.resolveMap(source, context);
    }

    static Object lookup(Map<String, Object> context, String path) {
        return SoarExpression.lookup(context, path);
    }
}
