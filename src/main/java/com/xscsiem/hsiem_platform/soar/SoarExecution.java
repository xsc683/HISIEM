package com.xscsiem.hsiem_platform.soar;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record SoarExecution(
        String id,
        String tenantId,
        String playbookId,
        String playbookName,
        long playbookRevision,
        PlaybookGraph graphSnapshot,
        String objectType,
        String objectId,
        String eventType,
        String triggerMessageId,
        SoarTriggerEnvelope triggerEnvelope,
        Map<String, Object> payloadSnapshot,
        String status,
        String currentNodeId,
        Instant nextRunAt,
        String error,
        String actor,
        boolean cancelRequested,
        @JsonIgnore String leaseOwner,
        @JsonIgnore Instant leaseExpiresAt,
        @JsonIgnore long fencingToken,
        Instant createdAt,
        Instant updatedAt,
        Instant startedAt,
        Instant finishedAt,
        List<NodeRun> nodeRuns) {

    public record NodeRun(
            String id,
            String executionId,
            String nodeId,
            String nodeName,
            String nodeType,
            String status,
            long sequenceNo,
            int visitNo,
            int attempt,
            String tokenId,
            String idempotencyKey,
            Map<String, Object> input,
            Map<String, Object> output,
            String error,
            Instant startedAt,
            Instant finishedAt) {
    }
}
