package com.xscsiem.hsiem_platform.soar;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** SOAR Playbook 查询、手动触发、审批和执行追踪 API。 */
@RestController
@RequestMapping("/api/soar")
public class SoarController {

    private final SoarService service;

    public SoarController(SoarService service) {
        this.service = service;
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

    @PostMapping("/executions")
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
