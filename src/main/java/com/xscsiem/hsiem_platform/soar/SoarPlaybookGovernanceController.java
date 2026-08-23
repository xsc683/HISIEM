package com.xscsiem.hsiem_platform.soar;

import com.xscsiem.hsiem_platform.tenant.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import com.xscsiem.hsiem_platform.control.ControlPlaneStore;

/** 可视化编辑器使用的草稿、四眼审批和灰度发布 API。 */
@RestController
@RequestMapping("/api/soar/designer")
public class SoarPlaybookGovernanceController {

    private final SoarPlaybookCatalog catalog;
    private final ControlPlaneStore control;

    public SoarPlaybookGovernanceController(SoarPlaybookCatalog catalog, ControlPlaneStore control) {
        this.catalog = catalog;
        this.control = control;
    }

    @GetMapping("/revisions")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST', 'AUDIT')")
    public List<SoarPlaybookRevision> revisions(@RequestParam(required = false) String state) {
        return catalog.listRevisions(TenantContext.id(), state);
    }

    @PostMapping("/drafts")
    @PreAuthorize("hasRole('ADMIN')")
    public SoarPlaybookRevision draft(@Valid @RequestBody DraftRequest request,
                                      Authentication authentication) {
        SoarPlaybookRevision result = catalog.createDraft(TenantContext.id(), request.definition(),
                request.layout(), authentication.getName());
        audit(authentication, "soar.playbook_draft_created", result);
        return result;
    }

    @PutMapping("/{playbookId}/revisions/{revision}")
    @PreAuthorize("hasRole('ADMIN')")
    public SoarPlaybookRevision update(@PathVariable String playbookId, @PathVariable int revision,
                                       @Valid @RequestBody UpdateRequest request,
                                       Authentication authentication) {
        SoarPlaybookRevision result = catalog.updateDraft(TenantContext.id(), playbookId, revision,
                request.definition(), request.layout(), request.lockVersion());
        audit(authentication, "soar.playbook_draft_updated", result);
        return result;
    }

    @PostMapping("/{playbookId}/revisions/{revision}/submit")
    @PreAuthorize("hasRole('ADMIN')")
    public SoarPlaybookRevision submit(@PathVariable String playbookId, @PathVariable int revision,
                                       Authentication authentication) {
        SoarPlaybookRevision result = catalog.submit(TenantContext.id(), playbookId, revision,
                authentication.getName());
        audit(authentication, "soar.playbook_submitted", result);
        return result;
    }

    @PostMapping("/{playbookId}/revisions/{revision}/review")
    @PreAuthorize("hasRole('ADMIN')")
    public SoarPlaybookRevision review(@PathVariable String playbookId, @PathVariable int revision,
                                       @RequestBody ReviewRequest request, Authentication authentication) {
        SoarPlaybookRevision result = catalog.review(TenantContext.id(), playbookId, revision,
                request.approved(), request.note(), authentication.getName());
        audit(authentication, request.approved() ? "soar.playbook_approved" : "soar.playbook_rejected", result);
        return result;
    }

    @PostMapping("/{playbookId}/revisions/{revision}/publish")
    @PreAuthorize("hasRole('ADMIN')")
    public SoarPlaybookRevision publish(@PathVariable String playbookId, @PathVariable int revision,
                                        @Valid @RequestBody PublishRequest request,
                                        Authentication authentication) {
        SoarPlaybookRevision result = catalog.publish(TenantContext.id(), playbookId, revision,
                request.rolloutPercentage(), authentication.getName());
        audit(authentication, "soar.playbook_published", result);
        return result;
    }

    @PostMapping("/import-git")
    @PreAuthorize("hasRole('ADMIN')")
    public List<SoarPlaybookRevision> importGit(Authentication authentication) {
        List<SoarPlaybookRevision> result = catalog.importGitAsDraft(TenantContext.id(), authentication.getName());
        control.audit(authentication.getName(), "soar.playbook_git_import",
                TenantContext.id() + ":count=" + result.size());
        return result;
    }

    private void audit(Authentication authentication, String action, SoarPlaybookRevision revision) {
        control.audit(authentication.getName(), action, revision.tenantId() + ":"
                + revision.playbookId() + ":r" + revision.revision());
    }

    public record DraftRequest(@NotNull SoarPlaybook definition, Map<String, Object> layout) {
    }

    public record UpdateRequest(@NotNull SoarPlaybook definition, Map<String, Object> layout,
                                long lockVersion) {
    }

    public record ReviewRequest(boolean approved, String note) {
    }

    public record PublishRequest(@Min(1) @Max(100) int rolloutPercentage) {
    }
}
