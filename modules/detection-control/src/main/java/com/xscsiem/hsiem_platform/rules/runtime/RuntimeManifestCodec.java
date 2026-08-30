package com.xscsiem.hsiem_platform.rules.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/** Stable JSON wire codec and SHA-256 hash authority for runtime manifests. */
public final class RuntimeManifestCodec {

    public static final Set<String> SUPPORTED_SCHEMA_VERSIONS = Set.of(
            RuntimeManifest.SCHEMA_VERSION, "v1");

    private final ObjectMapper mapper;

    public RuntimeManifestCodec() {
        this(new ObjectMapper());
    }

    public RuntimeManifestCodec(ObjectMapper mapper) {
        this.mapper = mapper.copy()
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, false)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, false)
                .configure(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, true);
    }

    public String encode(RuntimeManifest manifest) {
        validateSchemaVersion(manifest);
        try {
            return mapper.writeValueAsString(wireValue(manifest));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("runtime manifest serialization failed", e);
        }
    }

    public String toJson(RuntimeManifest manifest) {
        return encode(manifest);
    }

    public String specHash(RuntimeManifest manifest) {
        validateSchemaVersion(manifest);
        return sha256(canonicalSpecJson(manifest));
    }

    public String canonicalSpecJson(RuntimeManifest manifest) {
        validateSchemaVersion(manifest);
        try {
            return mapper.writeValueAsString(hashValue(manifest));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("runtime manifest hash serialization failed", e);
        }
    }

    /** SHA-256 over the supplied canonical UTF-8 JSON, returned as lowercase hexadecimal. */
    public String sha256(String canonicalJson) {
        if (canonicalJson == null) {
            throw new IllegalArgumentException("canonical JSON must not be null");
        }
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonicalJson.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public RuntimeManifest decode(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("runtime manifest JSON must not be blank");
        }
        try {
            JsonNode root = mapper.readTree(json);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("runtime manifest must be a JSON object");
            }
            String schemaVersion = requiredText(root, "schemaVersion");
            validateSchemaVersion(schemaVersion);
            String tenantId = requiredText(root, "tenantId");
            String targetCluster = requiredText(root, "targetCluster");
            String jobGroupKey = requiredText(root, "jobGroupKey");
            JsonNode generationNode = root.get("generation");
            if (generationNode == null || !generationNode.isIntegralNumber()) {
                throw new IllegalArgumentException("generation must be an integer");
            }
            long generation = generationNode.longValue();
            String jobId = optionalText(root.get("jobId"), "jobId");
            String jobKey = optionalText(root.get("jobKey"), "jobKey");
            JsonNode membersNode = root.get("members");
            if (membersNode == null || !membersNode.isArray()) {
                throw new IllegalArgumentException("members must be an array");
            }
            List<RuntimeManifest.Member> members = new ArrayList<>();
            for (JsonNode member : membersNode) {
                if (!member.isObject()) {
                    throw new IllegalArgumentException("manifest member must be an object");
                }
                JsonNode revisionNode = member.get("revision");
                if (revisionNode == null || !revisionNode.isIntegralNumber()) {
                    throw new IllegalArgumentException("member revision must be an integer");
                }
                members.add(new RuntimeManifest.Member(
                        requiredText(member, "ruleKey"),
                        revisionNode.longValue(),
                        requiredText(member, "planHash")));
            }
            return new RuntimeManifest(schemaVersion, tenantId, targetCluster, jobGroupKey,
                    generation, jobId, jobKey, members);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("invalid runtime manifest JSON", e);
        }
    }

    public void validateSchemaVersion(RuntimeManifest manifest) {
        if (manifest == null) {
            throw new IllegalArgumentException("runtime manifest must not be null");
        }
        validateSchemaVersion(manifest.schemaVersion());
    }

    public void validateSchemaVersion(String schemaVersion) {
        if (schemaVersion == null || !SUPPORTED_SCHEMA_VERSIONS.contains(schemaVersion)) {
            throw new IllegalArgumentException("unsupported runtime manifest schemaVersion: " + schemaVersion);
        }
    }

    private LinkedHashMap<String, Object> wireValue(RuntimeManifest manifest) {
        LinkedHashMap<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", manifest.schemaVersion());
        root.put("tenantId", manifest.tenantId());
        root.put("targetCluster", manifest.targetCluster());
        root.put("jobGroupKey", manifest.jobGroupKey());
        root.put("generation", manifest.generation());
        root.put("jobId", manifest.jobId());
        root.put("jobKey", manifest.jobKey());
        List<LinkedHashMap<String, Object>> members = new ArrayList<>();
        for (RuntimeManifest.Member member : manifest.members()) {
            LinkedHashMap<String, Object> value = new LinkedHashMap<>();
            value.put("ruleKey", member.ruleKey());
            value.put("revision", member.revision());
            value.put("planHash", member.planHash());
            members.add(value);
        }
        root.put("members", members);
        return root;
    }

    private LinkedHashMap<String, Object> hashValue(RuntimeManifest manifest) {
        LinkedHashMap<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", manifest.schemaVersion());
        root.put("tenantId", manifest.tenantId());
        root.put("targetCluster", manifest.targetCluster());
        root.put("jobGroupKey", manifest.jobGroupKey());
        root.put("generation", manifest.generation());
        List<LinkedHashMap<String, Object>> members = new ArrayList<>();
        for (RuntimeManifest.Member member : manifest.members()) {
            LinkedHashMap<String, Object> value = new LinkedHashMap<>();
            value.put("ruleKey", member.ruleKey());
            value.put("revision", member.revision());
            value.put("planHash", member.planHash());
            members.add(value);
        }
        root.put("members", members);
        return root;
    }

    private static String requiredText(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException(field + " must be a non-blank string");
        }
        return value.textValue();
    }

    private static String optionalText(JsonNode value, String field) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException(field + " must be null or a non-blank string");
        }
        return value.textValue();
    }

}
