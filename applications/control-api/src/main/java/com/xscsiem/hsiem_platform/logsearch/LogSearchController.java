package com.xscsiem.hsiem_platform.logsearch;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 安全日志检索 API：只接受结构化条件，不透传 Query DSL。 */
@RestController
@RequestMapping("/api/log-search")
public class LogSearchController {

    private final LogSearchService service;

    public LogSearchController(LogSearchService service) {
        this.service = service;
    }

    @GetMapping("/fields")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST', 'AUDIT')")
    public LogSearchCatalog fields() {
        return service.catalog();
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST', 'AUDIT')")
    public LogSearchResponse search(@RequestBody(required = false) LogSearchRequest request) {
        return service.search(request);
    }
}
