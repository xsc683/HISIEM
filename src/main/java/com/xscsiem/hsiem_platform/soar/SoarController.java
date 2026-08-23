package com.xscsiem.hsiem_platform.soar;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/soar")
public class SoarController {

    private final SoarService service;
    private final SoarDictionary dictionary;

    public SoarController(SoarService service, SoarDictionary dictionary) {
        this.service = service;
        this.dictionary = dictionary;
    }

    @GetMapping("/playbooks")
    @PreAuthorize("hasAnyRole('ADMIN','ANALYST','OPS','AUDIT')")
    public List<SoarPlaybook> listPlaybooks() {
        return service.listPlaybooks();
    }

    @GetMapping("/playbooks/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','ANALYST','OPS','AUDIT')")
    public SoarPlaybook getPlaybook(@PathVariable String id) {
        return service.getPlaybook(id);
    }

    @PostMapping("/playbooks")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<SoarPlaybook> createPlaybook(@RequestBody CreatePlaybookRequest request) {
        SoarPlaybook created = service.createPlaybook(request.name(), request.description(),
                request.entryType(), request.eventTypes(), actor());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/playbooks/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public SoarPlaybook updatePlaybook(@PathVariable String id,
                                       @RequestBody UpdatePlaybookRequest request) {
        return service.updatePlaybook(id, request.name(), request.description(), request.entryType(),
                request.eventTypes(), request.graph(), request.revision(), actor());
    }

    @PostMapping("/playbooks/{id}/publish")
    @PreAuthorize("hasRole('ADMIN')")
    public SoarPlaybook publishPlaybook(@PathVariable String id, @RequestBody RevisionRequest request) {
        return service.publishPlaybook(id, request.revision(), actor());
    }

    @PatchMapping("/playbooks/{id}/enabled")
    @PreAuthorize("hasRole('ADMIN')")
    public SoarPlaybook setEnabled(@PathVariable String id, @RequestBody EnabledRequest request) {
        return service.setEnabled(id, request.enabled(), actor());
    }

    @DeleteMapping("/playbooks/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deletePlaybook(@PathVariable String id) {
        service.deletePlaybook(id, actor());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/executions")
    @PreAuthorize("hasAnyRole('ADMIN','ANALYST','OPS','AUDIT')")
    public List<SoarExecution> listExecutions(@RequestParam(required = false) String status,
                                              @RequestParam(defaultValue = "100") int size) {
        return service.listExecutions(status, size);
    }

    @GetMapping("/executions/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','ANALYST','OPS','AUDIT')")
    public SoarExecution getExecution(@PathVariable String id) {
        return service.getExecution(id);
    }

    @PostMapping("/executions/{id}/cancel")
    @PreAuthorize("hasAnyRole('ADMIN','ANALYST')")
    public ResponseEntity<Void> cancelExecution(@PathVariable String id) {
        service.cancelExecution(id, actor());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/approvals")
    @PreAuthorize("hasAnyRole('ADMIN','ANALYST','AUDIT')")
    public List<SoarApproval> listApprovals(@RequestParam(required = false) String status,
                                            @RequestParam(defaultValue = "100") int size) {
        return service.listApprovals(status, size);
    }

    @PostMapping("/approvals/{id}/approve")
    @PreAuthorize("hasAnyRole('ADMIN','ANALYST')")
    public SoarApproval approve(@PathVariable String id, @RequestBody(required = false) DecisionRequest request) {
        return service.decideApproval(id, true, request == null ? null : request.note(), actor());
    }

    @PostMapping("/approvals/{id}/reject")
    @PreAuthorize("hasAnyRole('ADMIN','ANALYST')")
    public SoarApproval reject(@PathVariable String id, @RequestBody(required = false) DecisionRequest request) {
        return service.decideApproval(id, false, request == null ? null : request.note(), actor());
    }

    @GetMapping("/field-dictionary")
    @PreAuthorize("hasAnyRole('ADMIN','ANALYST','OPS','AUDIT')")
    public List<SoarDictionary.FieldDefinition> fieldDictionary(@RequestParam String objectType) {
        return dictionary.fields(objectType);
    }

    @GetMapping("/action-dictionary")
    @PreAuthorize("hasAnyRole('ADMIN','ANALYST','OPS','AUDIT')")
    public List<SoarDictionary.ActionDefinition> actionDictionary(@RequestParam String objectType) {
        return dictionary.actions(objectType);
    }

    private String actor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null || authentication.getName() == null ? "system" : authentication.getName();
    }

    public record CreatePlaybookRequest(String name, String description, String entryType,
                                        List<String> eventTypes) { }

    public record UpdatePlaybookRequest(String name, String description, String entryType,
                                        List<String> eventTypes, PlaybookGraph graph, long revision) { }

    public record RevisionRequest(long revision) { }

    public record EnabledRequest(boolean enabled) { }

    public record DecisionRequest(String note) { }
}
