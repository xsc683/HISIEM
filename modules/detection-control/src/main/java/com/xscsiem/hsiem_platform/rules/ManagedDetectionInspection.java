package com.xscsiem.hsiem_platform.rules;

import java.util.Map;

/** Typed managed-detection view; dynamic YAML/runtime payloads remain boundary maps. */
public record ManagedDetectionInspection(
        Map<String, Object> rule,
        RuleRevision revision,
        DetectionPlanArtifact plan,
        RuleDeployment deployment,
        Map<String, Object> runtime) {}
