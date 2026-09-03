package com.xscsiem.hsiem_platform.rules;

import java.util.Map;
import java.util.UUID;

/** Typed desired-state command parsed at the HTTP boundary. */
public record DeploymentCommand(UUID revisionId, String targetCluster) {
    public static DeploymentCommand empty() {
        return new DeploymentCommand(null, null);
    }

    public static DeploymentCommand fromApi(Map<String, Object> body) {
        if (body == null || body.isEmpty()) return empty();
        UUID revisionId = null;
        Object rawRevisionId = body.get("revisionId");
        if (rawRevisionId != null) {
            try {
                revisionId = UUID.fromString(String.valueOf(rawRevisionId));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("revisionId must be a UUID", e);
            }
        }
        Object rawCluster = body.get("targetCluster");
        String targetCluster = rawCluster == null ? null : String.valueOf(rawCluster);
        return new DeploymentCommand(revisionId, targetCluster);
    }
}
