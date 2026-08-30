package com.xscsiem.hsiem_platform.soar;

import java.time.Instant;
import java.util.List;

public record SoarPlaybook(
        String id,
        String tenantId,
        String name,
        String description,
        String status,
        boolean enabled,
        String entryType,
        List<String> eventTypes,
        PlaybookGraph graph,
        long revision,
        String createdBy,
        String updatedBy,
        Instant createdAt,
        Instant updatedAt,
        Instant publishedAt) {
}
