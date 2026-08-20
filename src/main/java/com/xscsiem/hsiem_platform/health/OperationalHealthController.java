package com.xscsiem.hsiem_platform.health;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 运维健康扫描 API，避免把 Docker 细节暴露给前端。 */
@RestController
@RequestMapping("/api/ops")
public class OperationalHealthController {

    private final OperationalHealthService service;

    public OperationalHealthController(OperationalHealthService service) {
        this.service = service;
    }

    @GetMapping("/health-scan")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPS', 'AUDIT')")
    public Map<String, Object> scan() {
        return service.scan();
    }
}
