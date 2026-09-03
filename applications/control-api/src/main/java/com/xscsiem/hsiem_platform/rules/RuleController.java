package com.xscsiem.hsiem_platform.rules;

import com.xscsiem.hsiem_platform.tenant.TenantContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Detection-rule authoring and desired-state HTTP API. */
@RestController
@RequestMapping("/api/detection-rules")
public class RuleController {

    private final RuleService rules;
    private final ManagedDetectionService managed;

    public RuleController(RuleService rules) {
        this(rules, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RuleController(RuleService rules, ManagedDetectionService managed) {
        this.rules = rules;
        this.managed = managed;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST', 'OPS', 'AUDIT')")
    public List<Map<String, Object>> list() {
        return rules.list();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST', 'OPS', 'AUDIT')")
    public Map<String, Object> detail(@PathVariable String id) {
        return rules.get(id);
    }

    @GetMapping("/{id}/runtime")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST', 'OPS', 'AUDIT')")
    public Map<String, Object> runtime(@PathVariable String id) {
        requireManagedRuntime();
        return inspectionResponse(managed.inspect(id, operator()));
    }

    /** Desired-state API; physical deployment is performed asynchronously by the controller. */
    @PostMapping("/{id}/deploy")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> deployDesired(
            @PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        requireManagedRuntime();
        return ResponseEntity.accepted()
                .body(
                        deploymentResponse(
                                managed.deploy(
                                        TenantContext.id(),
                                        id,
                                        DeploymentCommand.fromApi(body),
                                        operator())));
    }

    @PostMapping("/{id}/stop")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> stopDesired(@PathVariable String id) {
        requireManagedRuntime();
        return ResponseEntity.accepted()
                .body(deploymentResponse(managed.stop(TenantContext.id(), id, operator())));
    }

    @PostMapping("/{id}/rollback")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> rollback(
            @PathVariable String id, @RequestBody Map<String, Object> body) {
        requireManagedRuntime();
        return ResponseEntity.accepted()
                .body(
                        deploymentResponse(
                                managed.rollback(
                                        TenantContext.id(),
                                        id,
                                        DeploymentCommand.fromApi(body),
                                        operator())));
    }

    private void requireManagedRuntime() {
        if (managed == null) {
            throw new IllegalStateException("managed detection runtime is unavailable");
        }
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        Map<String, Object> created = rules.create(body, operator());
        created.put("deployed", false);
        created.put("redeployRequired", true);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> update(
            @PathVariable String id, @RequestBody Map<String, Object> body) {
        Map<String, Object> updated = rules.update(id, body, operator());
        updated.put("deployed", false);
        updated.put("redeployRequired", true);
        return updated;
    }

    @GetMapping("/{id}/hits")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST', 'AUDIT')")
    public Map<String, Object> hits(
            @PathVariable String id, @RequestParam(defaultValue = "7d") String range) {
        return Map.of("ruleId", id, "range", range, "count", rules.hits(id, range));
    }

    /**
     * Toggle authoring state in YAML; desired-state reconciliation is handled by the controller.
     */
    @PostMapping("/{id}/toggle")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> toggle(@PathVariable String id) {
        Map<String, Object> updated = rules.toggle(id, operator());
        updated.put("deployed", false);
        updated.put("redeployRequired", true);
        updated.put(
                "note",
                "authoring state updated in infra/rules; deploy desired state for controller reconciliation");
        return updated;
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> patch(
            @PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> current = rules.get(id);
        Object requested = body == null ? null : body.get("enabled");
        Map<String, Object> updated = current;
        if (!(requested instanceof Boolean desired)
                || desired != Boolean.TRUE.equals(current.get("enabled"))) {
            updated = rules.toggle(id, operator());
        }
        updated.put("deployed", false);
        updated.put("redeployRequired", true);
        updated.put(
                "note",
                "authoring state updated in infra/rules; deploy desired state for controller reconciliation");
        return updated;
    }

    @GetMapping("/mitre")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST', 'AUDIT')")
    public Map<String, Object> mitre() {
        return rules.mitre();
    }

    /**
     * Compatibility bulk-deploy endpoint; it records desired state for controller reconciliation.
     */
    @PostMapping("/deploy")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> deploy() {
        Map<String, Object> response = new LinkedHashMap<>();
        List<DeploymentSummary> summaries =
                managed == null
                        ? List.of()
                        : managed.deployAll(TenantContext.id(), rules.list(), operator());
        List<Map<String, Object>> summaryViews =
                summaries.stream().map(RuleController::summaryResponse).toList();
        response.put("status", "PENDING");
        response.put("jobId", null);
        response.put("summaries", summaryViews);
        response.put("pendingSummaries", summaryViews);
        response.put(
                "note",
                "desired state persisted as PENDING; the detection controller performs physical Flink reconciliation");
        return ResponseEntity.accepted().body(response);
    }

    private static Map<String, Object> inspectionResponse(ManagedDetectionInspection inspection) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("rule", inspection.rule());
        response.put(
                "revision",
                inspection.revision() == null ? null : revisionResponse(inspection.revision()));
        response.put("plan", inspection.plan() == null ? null : planResponse(inspection.plan()));
        response.put(
                "deployment",
                inspection.deployment() == null
                        ? null
                        : deploymentResponse(inspection.deployment()));
        Map<String, Object> runtime = inspection.runtime();
        response.put("assignment", runtime.get("assignment"));
        response.put("jobGroup", runtime.get("jobGroup"));
        response.put("runtimeStatus", runtime.get("runtimeStatus"));
        response.put("desiredVsObserved", runtime.get("desiredVsObserved"));
        return response;
    }

    private static Map<String, Object> deploymentResponse(RuleDeployment deployment) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("deployment_id", deployment.deploymentId());
        response.put("tenant_id", deployment.tenantId());
        response.put("rule_key", deployment.ruleKey());
        response.put("desired_revision_id", deployment.desiredRevisionId());
        response.put("desired_state", deployment.desiredState().name());
        response.put("generation", deployment.generation());
        response.put("observed_generation", deployment.observedGeneration());
        response.put("target_cluster", deployment.targetCluster());
        response.put("status", deployment.status());
        response.put("last_error", deployment.lastError());
        response.put("created_at", deployment.createdAt());
        response.put("updated_at", deployment.updatedAt());
        return response;
    }

    private static Map<String, Object> revisionResponse(RuleRevision revision) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("revisionId", revision.revisionId());
        response.put("revision", revision.revision());
        response.put("contentHash", revision.contentHash());
        response.put("sourceCommit", revision.sourceCommit());
        response.put("createdBy", revision.createdBy());
        response.put("createdAt", revision.createdAt());
        return response;
    }

    private static Map<String, Object> planResponse(DetectionPlanArtifact plan) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("planId", plan.planId());
        response.put("compilerVersion", plan.compilerVersion());
        response.put("planHash", plan.planHash());
        response.put("plan", plan.planJson());
        response.put("createdAt", plan.createdAt());
        return response;
    }

    private static Map<String, Object> summaryResponse(DeploymentSummary summary) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ruleKey", summary.ruleKey());
        response.put("deploymentId", summary.deploymentId());
        response.put("desiredState", summary.desiredState().name());
        response.put("targetCluster", summary.targetCluster());
        response.put("generation", summary.generation());
        response.put("status", summary.status());
        return response;
    }

    private String operator() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null || authentication.getName() == null
                ? "system"
                : authentication.getName();
    }
}
