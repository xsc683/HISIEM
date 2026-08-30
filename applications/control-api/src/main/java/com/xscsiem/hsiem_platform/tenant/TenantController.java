package com.xscsiem.hsiem_platform.tenant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tenants")
public class TenantController {

    private final TenantService tenants;

    public TenantController(TenantService tenants) {
        this.tenants = tenants;
    }

    @GetMapping("/mine")
    public List<Map<String, Object>> mine(Authentication authentication) {
        return tenants.listForUser(authentication.getName());
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public List<Map<String, Object>> all() {
        return tenants.listAll();
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> create(@Valid @RequestBody CreateRequest request,
                                      Authentication authentication) {
        return tenants.create(request.id(), request.name(), authentication.getName());
    }

    @PutMapping("/{tenantId}/members/{username}")
    @PreAuthorize("hasRole('ADMIN')")
    public void member(@PathVariable String tenantId, @PathVariable String username,
                       @Valid @RequestBody MemberRequest request) {
        tenants.addMember(tenantId, username, request.tenantRole());
    }

    public record CreateRequest(@NotBlank String id, @NotBlank String name) {
    }

    public record MemberRequest(@NotBlank String tenantRole) {
    }
}
