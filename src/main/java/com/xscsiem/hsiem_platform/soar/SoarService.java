package com.xscsiem.hsiem_platform.soar;

import com.xscsiem.hsiem_platform.control.ControlPlaneStore;
import com.xscsiem.hsiem_platform.tenant.TenantContext;
import org.springframework.stereotype.Service;

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
