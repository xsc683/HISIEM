package com.xscsiem.hsiem_platform.rules;

import java.time.Instant;
import java.util.UUID;

/** Immutable persisted authoring snapshot. */
public record RuleRevision(
        UUID revisionId,
        String ruleKey,
        int revision,
        String definitionJson,
        String contentHash,
        String sourceCommit,
        String createdBy,
        Instant createdAt) {}
