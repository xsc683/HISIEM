package com.siem;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.siem.config.RuleDecl;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Verifies the immutable runtime manifest before any rule is registered in Flink. */
public final class RuntimeManifestVerifier {

    private static final String SUPPORTED_SCHEMA = "1";
    private final ObjectMapper mapper;

    public RuntimeManifestVerifier() {
        this(new ObjectMapper());
    }

    public RuntimeManifestVerifier(ObjectMapper mapper) {
        this.mapper = mapper.copy();
    }

    /**
     * Read and verify {@code runtime-manifest.json} using its exact UTF-8 bytes.  The returned
     * value is the parsed member set and is intentionally not used as an expected substitute for
     * the rules actually loaded by {@link com.siem.config.RuleConfigLoader}.
     */
    public Verification verify(Path rulesDir, DetectionJobArguments arguments,
                               List<RuleDecl> loadedRules) {
        if (arguments == null) throw new IllegalArgumentException("arguments must not be null");
        if (loadedRules == null) throw new IllegalArgumentException("loadedRules must not be null");
        if (arguments.legacy()) {
            System.out.println("[DetectionJob] legacy launch: runtime manifest verification skipped");
            return Verification.legacyVerification();
        }
        if (rulesDir == null) throw new IllegalArgumentException("rulesDir must not be null");
        Path manifest = rulesDir.resolve("runtime-manifest.json").normalize();
        byte[] raw;
        try {
            raw = Files.readAllBytes(manifest);
        } catch (IOException e) {
            throw new IllegalStateException("runtime manifest cannot be read: " + manifest, e);
        }
        String actualHash = sha256(raw);
        if (!actualHash.equalsIgnoreCase(arguments.manifestHash())) {
            throw new IllegalStateException("runtime manifest SHA-256 mismatch: expected "
                    + arguments.manifestHash() + ", actual " + actualHash);
        }

        JsonNode root;
        try {
            root = mapper.readTree(decodeUtf8(raw));
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("runtime manifest is not valid JSON: " + manifest, e);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalStateException("runtime manifest must be a JSON object");
        }
        String schema = text(root, "schemaVersion");
        if (!SUPPORTED_SCHEMA.equals(schema) && !"v1".equals(schema)) {
            throw new IllegalStateException("unsupported runtime manifest schemaVersion: " + schema);
        }
        JsonNode generationNode = root.get("generation");
        if (generationNode == null || !generationNode.isIntegralNumber()
                || generationNode.longValue() != arguments.generation()) {
            throw new IllegalStateException("runtime manifest generation does not match job arguments");
        }
        JsonNode membersNode = root.get("members");
        if (membersNode == null || !membersNode.isArray()) {
            throw new IllegalStateException("runtime manifest members must be an array");
        }
        Set<String> manifestIds = new TreeSet<>();
        for (JsonNode member : membersNode) {
            if (member == null || !member.isObject()) {
                throw new IllegalStateException("runtime manifest member must be an object");
            }
            String id = text(member, "ruleKey");
            if (!manifestIds.add(id)) {
                throw new IllegalStateException("duplicate ruleKey in runtime manifest: " + id);
            }
        }
        Set<String> loadedIds = new TreeSet<>();
        for (RuleDecl rule : loadedRules) {
            if (rule == null || rule.id == null || rule.id.isBlank()) {
                throw new IllegalStateException("loaded rule has a blank id");
            }
            if (!loadedIds.add(rule.id)) {
                throw new IllegalStateException("duplicate loaded rule id: " + rule.id);
            }
        }
        if (!manifestIds.equals(loadedIds)) {
            Set<String> missing = new TreeSet<>(manifestIds);
            missing.removeAll(loadedIds);
            Set<String> extra = new TreeSet<>(loadedIds);
            extra.removeAll(manifestIds);
            throw new IllegalStateException("runtime manifest rule IDs differ from loaded rules; missing="
                    + missing + ", extra=" + extra);
        }
        return new Verification(false, actualHash, arguments.generation(), Set.copyOf(manifestIds));
    }

    public Verification verify(String rulesDir, DetectionJobArguments arguments,
                               List<RuleDecl> loadedRules) {
        return verify(Path.of(rulesDir), arguments, loadedRules);
    }

    public Verification verify(Path rulesDir, DetectionJobArguments arguments,
                               RuleDecl... loadedRules) {
        return verify(rulesDir, arguments, List.of(loadedRules));
    }

    private static String decodeUtf8(byte[] raw) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(raw))
                .toString();
    }

    private static String text(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalStateException("runtime manifest field " + field + " must be a non-blank string");
        }
        return value.textValue();
    }

    private static String sha256(byte[] raw) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(raw));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public record Verification(boolean legacy, String rawHash, long generation,
                               Set<String> ruleKeys) {
        public Verification {
            ruleKeys = ruleKeys == null ? Set.of() : Set.copyOf(new HashSet<>(ruleKeys));
        }

        public static Verification legacyVerification() {
            return new Verification(true, null, 0L, Set.of());
        }
    }
}
