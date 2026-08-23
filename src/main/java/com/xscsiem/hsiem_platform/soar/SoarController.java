package com.xscsiem.hsiem_platform.soar;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** SOAR Playbook 查询、手动触发、审批和执行追踪 API。 */
@RestController
@RequestMapping("/api/soar")
public class SoarController {

    private final SoarService service;
    private final SoarConnectorRegistry connectors;
    private final SoarTriggerScanner triggerScanner;

    public SoarController(SoarService service, SoarConnectorRegistry connectors,
                          SoarTriggerScanner triggerScanner) {
        this.service = service;
        this.connectors = connectors;
        this.triggerScanner = triggerScanner;
    }

    @GetMapping("/playbooks")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST', 'AUDIT')")
    public List<SoarPlaybook> playbooks(@RequestParam(required = false) String resourceType) {
        return service.listPlaybooks(resourceType);
    }

    @GetMapping("/playbooks/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST', 'AUDIT')")
    public SoarPlaybook playbook(@PathVariable String id) {
        return service.playbook(id);
    }

    @PostMapping("/playbooks/reload")
    @PreAuthorize("hasRole('ADMIN')")
    public List<SoarPlaybook> reload(Authentication authentication) {
        return service.reload(authentication.getName());
    }

    @GetMapping("/executions")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST', 'AUDIT')")
    public List<SoarExecution> executions(@RequestParam(defaultValue = "50") int size) {
        return service.listExecutions(size);
    }

    @GetMapping("/executions/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST', 'AUDIT')")
    public SoarExecution execution(@PathVariable String id) {
        return service.detail(id);
    }

    @GetMapping("/executions/{id}/events")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST', 'AUDIT')")
    public List<SoarExecutionEvent> events(@PathVariable String id) {
        return service.events(id);
    }

    @GetMapping("/automation-rules")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST', 'AUDIT')")
    public List<Map<String, Object>> automationRules() {
        return service.automationRules();
    }

    @PostMapping("/automation-rules/scan")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> scanAutomationRules() {
        return triggerScanner.scanNow();
    }

    @GetMapping("/connectors")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST', 'AUDIT')")
    public List<Map<String, Object>> connectors() {
        return connectors.list().stream().map(SoarController::connectorView).toList();
    }

    @PostMapping("/connectors/reload")
    @PreAuthorize("hasRole('ADMIN')")
    public List<Map<String, Object>> reloadConnectors() {
        return connectors.reload().stream().map(SoarController::connectorView).toList();
    }

    @PostMapping("/executions")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST')")
    public SoarExecution start(@Valid @RequestBody StartRequest request, Authentication authentication) {
        return service.start(request.playbookId(), request.resourceType(), request.resourceId(),
                authentication.getName());
    }

    @PostMapping("/executions/{id}/approval")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST')")
    public SoarExecution approval(@PathVariable String id, @RequestBody ApprovalRequest request,
                                  Authentication authentication) {
        return service.approve(id, request.approved(), authentication.getName(), role(authentication));
    }

    @PostMapping("/executions/{id}/retry")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST')")
    public SoarExecution retry(@PathVariable String id, Authentication authentication) {
        return service.retry(id, authentication.getName());
    }

    @PostMapping("/executions/{id}/cancel")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST')")
    public SoarExecution cancel(@PathVariable String id, Authentication authentication) {
        return service.cancel(id, authentication.getName());
    }

    @PostMapping("/executions/{id}/pause")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST')")
    public SoarExecution pause(@PathVariable String id, Authentication authentication) {
        return service.pause(id, authentication.getName());
    }

    @PostMapping("/executions/{id}/resume")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST')")
    public SoarExecution resume(@PathVariable String id, Authentication authentication) {
        return service.resume(id, authentication.getName());
    }

    private static Map<String, Object> connectorView(SoarConnector connector) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", connector.id());
        view.put("name", connector.name());
        view.put("description", connector.description());
        view.put("enabled", connector.isEnabled());
        String environmentUrl = connector.baseUrlEnv() == null ? null
                : System.getenv(connector.baseUrlEnv());
        view.put("configured", connector.baseUrl() != null && !connector.baseUrl().isBlank()
                || environmentUrl != null && !environmentUrl.isBlank());
        view.put("actions", connector.actions() == null ? Map.of() : connector.actions());
        return view;
    }

    private static String role(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(Object::toString)
                .filter(authority -> authority.startsWith("ROLE_"))
                .map(authority -> authority.substring(5).toLowerCase())
                .findFirst().orElse("audit");
    }

    public record StartRequest(@NotBlank String playbookId, @NotBlank String resourceType,
                               @NotBlank String resourceId) {
    }

    public record ApprovalRequest(boolean approved) {
    }
}
