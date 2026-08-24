package com.xscsiem.hsiem_platform.soar;

import com.xscsiem.hsiem_platform.control.ControlPlaneStore;
import com.xscsiem.hsiem_platform.tenant.TenantContext;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SoarService {

    private final SoarStore store;
    private final SoarPlaybookValidator validator;
    private final ControlPlaneStore control;
    private final SoarGraphRouter router;

    public SoarService(SoarStore store, SoarPlaybookValidator validator,
                       ControlPlaneStore control, SoarGraphRouter router) {
        this.store = store;
        this.validator = validator;
        this.control = control;
        this.router = router;
    }

    public List<SoarPlaybook> listPlaybooks() {
        return store.listPlaybooks(TenantContext.id());
    }

    public SoarPlaybook getPlaybook(String id) {
        return store.getPlaybook(TenantContext.id(), id);
    }

    public SoarPlaybook createPlaybook(String name, String description, String entryType,
                                       List<String> eventTypes, String actor) {
        requireName(name);
        String normalizedType = normalizeType(entryType);
        List<String> normalizedEvents = normalizeEvents(normalizedType, eventTypes);
        PlaybookGraph graph = starterGraph();
        validator.validate(normalizedType, normalizedEvents, graph);
        SoarPlaybook created = store.createPlaybook(TenantContext.id(), name.trim(), description,
                normalizedType, normalizedEvents, graph, actor);
        audit(actor, "soar.playbook.create", created.id());
        return created;
    }

    public SoarPlaybook updatePlaybook(String id, String name, String description, String entryType,
                                       List<String> eventTypes, PlaybookGraph graph,
                                       long revision, String actor) {
        requireName(name);
        String normalizedType = normalizeType(entryType);
        List<String> normalizedEvents = normalizeEvents(normalizedType, eventTypes);
        validator.validateDraft(normalizedType, normalizedEvents, graph);
        SoarPlaybook updated = store.updatePlaybook(TenantContext.id(), id, name.trim(), description,
                normalizedType, normalizedEvents, graph, revision, actor);
        audit(actor, "soar.playbook.update", id + "@" + updated.revision());
        return updated;
    }

    public SoarPlaybook publishPlaybook(String id, long revision, String actor) {
        SoarPlaybook current = store.getPlaybook(TenantContext.id(), id);
        if (current.revision() != revision) {
            throw new com.xscsiem.hsiem_platform.onboarding.ConflictException(
                    "Playbook 已被修改，请刷新后再发布");
        }
        validator.validate(current.entryType(), current.eventTypes(), current.graph());
        SoarPlaybook published = store.publishPlaybook(TenantContext.id(), id, revision, actor);
        audit(actor, "soar.playbook.publish", id + "@" + published.revision());
        return published;
    }

    public SoarPlaybook setEnabled(String id, boolean enabled, String actor) {
        SoarPlaybook updated = store.setEnabled(TenantContext.id(), id, enabled, actor);
        audit(actor, enabled ? "soar.playbook.enable" : "soar.playbook.disable", id);
        return updated;
    }

    public void deletePlaybook(String id, String actor) {
        store.deletePlaybook(TenantContext.id(), id, actor);
        audit(actor, "soar.playbook.delete", id);
    }

    public List<SoarExecution> listExecutions(String status, int size) {
        return store.listExecutions(TenantContext.id(), status, size);
    }

    public SoarExecution getExecution(String id) {
        return store.getExecution(TenantContext.id(), id);
    }

    /** Starts one published playbook from the control plane. */
    public SoarExecution triggerExecution(String playbookId, String requestId,
                                          String objectType, String objectId,
                                          String eventType, Map<String, Object> payload,
                                          String actor) {
        SoarPlaybook playbook = store.getPlaybook(TenantContext.id(), playbookId);
        if (!"published".equals(playbook.status()) || !playbook.enabled()) {
            throw new com.xscsiem.hsiem_platform.onboarding.ConflictException(
                    "只有已发布且启用的 Playbook 才能手动触发");
        }
        String normalizedObjectType = objectType == null || objectType.isBlank()
                ? playbook.entryType() : objectType.trim().toLowerCase();
        if (!playbook.entryType().equals(normalizedObjectType)) {
            throw new IllegalArgumentException("手动触发对象类型必须与 Playbook 入口一致");
        }
        String normalizedEvent = eventType == null || eventType.isBlank()
                ? normalizedObjectType + ".created" : eventType.trim().toLowerCase();
        if (!playbook.eventTypes().contains(normalizedEvent)) {
            throw new IllegalArgumentException("手动触发事件不在 Playbook 订阅范围内");
        }
        if (objectId == null || objectId.isBlank()) {
            throw new IllegalArgumentException("手动触发 objectId 不能为空");
        }
        if (objectId.trim().length() > 256) {
            throw new IllegalArgumentException("手动触发 objectId 不能超过 256 个字符");
        }
        Map<String, Object> normalizedPayload = normalizeManualPayload(
                normalizedObjectType, objectId.trim(), payload);
        SoarTriggerEnvelope trigger = SoarTriggerEnvelope.manual(requestId, normalizedEvent,
                TenantContext.id(), normalizedObjectType, objectId.trim(), normalizedPayload, actor);
        store.createExecution(playbook, trigger, "manual:" + actor, "MANUAL");
        audit(actor, "soar.execution.manual", playbookId + ":" + trigger.messageId());
        return store.findExecutionByTrigger(TenantContext.id(), playbookId, trigger.messageId());
    }

    private Map<String, Object> normalizeManualPayload(String objectType, String objectId,
                                                       Map<String, Object> payload) {
        Map<String, Object> source = payload == null ? Map.of() : payload;
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> object = new LinkedHashMap<>();
        Object nested = source.get(objectType);
        if (nested != null) {
            if (!(nested instanceof Map<?, ?> values)) {
                throw new IllegalArgumentException("手动触发 payload." + objectType + " 必须是对象");
            }
            source.forEach(result::put);
            values.forEach((key, value) -> object.put(String.valueOf(key), value));
        } else {
            object.putAll(source);
        }
        Object suppliedId = object.get("id");
        if (suppliedId != null && !objectId.equals(String.valueOf(suppliedId))) {
            throw new IllegalArgumentException("手动触发 payload 中的对象 ID 与 objectId 不一致");
        }
        object.put("id", objectId);
        result.put(objectType, object);
        return result;
    }

    public void cancelExecution(String id, String actor) {
        store.requestCancel(TenantContext.id(), id);
        audit(actor, "soar.execution.cancel", id);
    }

    public List<SoarApproval> listApprovals(String status, int size) {
        return store.listApprovals(TenantContext.id(), status, size);
    }

    public SoarApproval decideApproval(String id, boolean approved, String note, String actor) {
        SoarApproval approval = store.getApproval(TenantContext.id(), id);
        SoarExecution execution = store.getExecution(TenantContext.id(), approval.executionId());
        String branch = approved ? "approve" : "reject";
        String nextNodeId = router.next(execution.graphSnapshot(), approval.nodeId(), branch);
        SoarApproval decided = store.decideApproval(TenantContext.id(), id,
                approved ? "approved" : "rejected", actor, note, nextNodeId);
        audit(actor, approved ? "soar.approval.approve" : "soar.approval.reject", id);
        return decided;
    }

    private PlaybookGraph starterGraph() {
        return new PlaybookGraph(List.of(
                new PlaybookGraph.Node("start", "开始", "start", Map.of(), 80, 180),
                new PlaybookGraph.Node("end", "结束", "end", Map.of(), 520, 180)),
                List.of(new PlaybookGraph.Edge("edge-start-end", "start", "end", "next")));
    }

    private List<String> normalizeEvents(String entryType, List<String> events) {
        List<String> result = events == null || events.isEmpty()
                ? List.of(entryType + ".created") : events.stream().distinct().toList();
        return result;
    }

    private String normalizeType(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private void requireName(String value) {
        if (value == null || value.isBlank() || value.length() > 256) {
            throw new IllegalArgumentException("Playbook 名称不能为空且不能超过 256 字符");
        }
    }

    private void audit(String actor, String action, String target) {
        control.audit(actor, action, TenantContext.id() + ":" + target);
    }
}
