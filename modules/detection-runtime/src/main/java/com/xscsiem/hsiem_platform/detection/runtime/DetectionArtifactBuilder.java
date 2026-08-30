package com.xscsiem.hsiem_platform.detection.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.xscsiem.hsiem_platform.rules.runtime.RuntimeManifest;
import com.xscsiem.hsiem_platform.rules.runtime.RuntimeManifestCodec;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Materializes and verifies immutable local rule artifacts for a claimed runtime target. */
public final class DetectionArtifactBuilder {

    private static final String MANIFEST_FILE = "runtime-manifest.json";
    private static final String METADATA_FILE = "artifact-metadata.json";
    private static final String DEFAULT_CONTAINER_ROOT = "/opt/flink/detection-artifacts";

    private final JdbcTemplate jdbc;
    private final RuntimeManifestCodec codec;
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .configure(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, true);
    private final Path artifactRoot;
    private final String containerArtifactRoot;
    private final DetectionJobNameCodec names;

    public DetectionArtifactBuilder(JdbcTemplate jdbc, RuntimeManifestCodec codec,
                                    Path artifactRoot, String containerArtifactRoot) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc, "jdbc must not be null");
        this.codec = java.util.Objects.requireNonNull(codec, "codec must not be null");
        this.artifactRoot = java.util.Objects.requireNonNull(artifactRoot, "artifactRoot must not be null")
                .toAbsolutePath().normalize();
        this.containerArtifactRoot = requiredContainerRoot(containerArtifactRoot);
        this.names = new DetectionJobNameCodec();
    }

    public DetectionArtifactBuilder(JdbcTemplate jdbc, RuntimeManifestCodec codec, Path artifactRoot) {
        this(jdbc, codec, artifactRoot, DEFAULT_CONTAINER_ROOT);
    }

    public DetectionArtifactBuilder(JdbcTemplate jdbc, RuntimeManifestCodec codec, String artifactRoot) {
        this(jdbc, codec, Path.of(artifactRoot), DEFAULT_CONTAINER_ROOT);
    }

    /** Return the safe local directory name for an already observed immutable artifact. */
    public Path artifactPath(String jobKey, long generation, String manifestHash) {
        DetectionJobNameCodec.validateJobKey(jobKey);
        if (generation < 0) throw new IllegalArgumentException("generation must be non-negative");
        DetectionJobNameCodec.validateHash(manifestHash);
        return safeResolve(safeResolve(artifactRoot, jobKey),
                generation + "-" + manifestHash.toLowerCase(Locale.ROOT));
    }

    public String containerArtifactPath(String jobKey, long generation, String manifestHash) {
        DetectionJobNameCodec.validateJobKey(jobKey);
        DetectionJobNameCodec.validateHash(manifestHash);
        if (generation < 0) throw new IllegalArgumentException("generation must be non-negative");
        return containerPath(jobKey, generation, manifestHash.toLowerCase(Locale.ROOT));
    }

    /** Build once or verify/reuse the existing immutable artifact for this exact target. */
    public DetectionArtifact build(DetectionRuntimeTarget target) {
        RuntimeManifest expected = validateExpected(target);
        if (expected.members().isEmpty()) {
            throw new IllegalArgumentException("cannot apply an empty expected manifest");
        }
        List<RuleRow> rows = loadRows(target, expected);
        validateRows(expected, rows);
        String jobKey = names.jobKey(target.tenantId(), target.targetCluster(), target.groupKey());
        String hash = codec.specHash(expected);
        Path jobRoot = safeResolve(artifactRoot, jobKey);
        Path finalPath = safeResolve(jobRoot, expected.generation() + "-" + hash);
        List<ArtifactFile> files = files(rows);
        String canonical = codec.canonicalSpecJson(expected);
        DetectionArtifact artifact = new DetectionArtifact(finalPath,
                containerPath(jobKey, expected.generation(), hash), jobKey,
                expected.generation(), hash);

        if (Files.exists(finalPath)) {
            verifyExisting(artifact, expected, canonical, files);
            return artifact;
        }

        Path temporary = jobRoot.resolve(".tmp-" + UUID.randomUUID()).normalize();
        ensureChild(artifactRoot, temporary);
        try {
            Files.createDirectories(temporary);
            writeFiles(temporary, canonical, expected, files, jobKey, target);
            moveNew(temporary, finalPath);
        } catch (FileAlreadyExistsException concurrent) {
            deleteTree(temporary);
            verifyExisting(artifact, expected, canonical, files);
            return artifact;
        } catch (RuntimeException | IOException failure) {
            deleteTree(temporary);
            if (failure instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("failed to materialize detection artifact", failure);
        }
        verifyExisting(artifact, expected, canonical, files);
        return artifact;
    }

    private RuntimeManifest validateExpected(DetectionRuntimeTarget target) {
        if (target == null) throw new IllegalArgumentException("target must not be null");
        RuntimeManifest expected = codec.decode(target.expectedManifestJson());
        codec.validateSchemaVersion(expected);
        if (!target.tenantId().equals(expected.tenantId())
                || !target.groupKey().equals(expected.jobGroupKey())
                || !target.targetCluster().equals(expected.targetCluster())
                || target.desiredGeneration() != expected.generation()) {
            throw new IllegalArgumentException("expected manifest scope or generation does not match target");
        }
        String actual = codec.specHash(expected);
        if (!actual.equalsIgnoreCase(target.expectedManifestHash())) {
            throw new IllegalArgumentException("expected manifest hash does not match target");
        }
        return expected;
    }

    private List<RuleRow> loadRows(DetectionRuntimeTarget target, RuntimeManifest expected) {
        // The tenant and group predicates are deliberately present on the assignment table; a
        // rule key alone is not a sufficient scope and must never select another tenant's revision.
        return jdbc.query("""
                SELECT a.rule_key, a.revision, a.plan_id, a.plan_hash, a.generation,
                       r.definition_json
                FROM rule_job_assignment a
                JOIN detection_plan p
                  ON p.plan_id = a.plan_id AND p.plan_hash = a.plan_hash
                JOIN rule_revision r
                  ON r.revision_id = p.revision_id
                 AND r.rule_key = a.rule_key
                 AND r.revision = a.revision
                WHERE a.tenant_id = ? AND a.group_key = ?
                ORDER BY a.rule_key
                """, (rs, rowNum) -> new RuleRow(
                rs.getString("rule_key"), rs.getLong("revision"),
                rs.getObject("plan_id", java.util.UUID.class), rs.getString("plan_hash"),
                rs.getLong("generation"), rs.getString("definition_json")),
                target.tenantId(), target.groupKey());
    }

    private void validateRows(RuntimeManifest expected, List<RuleRow> rows) {
        if (rows.size() != expected.members().size()) {
            throw new IllegalStateException("assignment members do not exactly match expected manifest: expected "
                    + expected.members().size() + ", actual " + rows.size());
        }
        Map<String, RuleRow> byKey = new LinkedHashMap<>();
        for (RuleRow row : rows) {
            if (byKey.put(row.ruleKey(), row) != null) {
                throw new IllegalStateException("duplicate assignment ruleKey: " + row.ruleKey());
            }
            if (row.definitionJson() == null || row.definitionJson().isBlank()) {
                throw new IllegalStateException("empty definition_json for " + row.ruleKey());
            }
            if (row.generation() != expected.generation()) {
                throw new IllegalStateException("assignment generation mismatch for " + row.ruleKey());
            }
        }
        for (RuntimeManifest.Member member : expected.members()) {
            RuleRow row = byKey.get(member.ruleKey());
            if (row == null || row.revision() != member.revision()
                    || !member.planHash().equals(row.planHash())) {
                throw new IllegalStateException("assignment does not match expected member: " + member.ruleKey());
            }
        }
    }

    private List<ArtifactFile> files(List<RuleRow> rows) {
        List<ArtifactFile> result = new ArrayList<>();
        int index = 1;
        for (RuleRow row : rows.stream().sorted(Comparator.comparing(RuleRow::ruleKey)).toList()) {
            result.add(new ArtifactFile(String.format(Locale.ROOT, "%04d-%s.yaml", index++, safeSlug(row.ruleKey())),
                    row.ruleKey(), runtimeDefinition(row.definitionJson())));
        }
        return List.copyOf(result);
    }

    /**
     * Runtime desired state is the source of truth for activation.  Revision JSON is immutable
     * history and can still contain enabled=false, so the immutable runtime copy must explicitly
     * enable every member selected by the assignment manifest.
     */
    private String runtimeDefinition(String definitionJson) {
        try {
            Map<String, Object> definition = mapper.readValue(definitionJson,
                    new TypeReference<>() { });
            definition.put("enabled", true);
            return mapper.writeValueAsString(definition);
        } catch (Exception e) {
            throw new IllegalStateException("definition_json must be a JSON object", e);
        }
    }

    private void writeFiles(Path directory, String canonical, RuntimeManifest expected,
                            List<ArtifactFile> files, String jobKey, DetectionRuntimeTarget target)
            throws IOException {
        Files.writeString(directory.resolve(MANIFEST_FILE), canonical, StandardCharsets.UTF_8);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("schemaVersion", RuntimeManifest.SCHEMA_VERSION);
        metadata.put("tenantId", target.tenantId());
        metadata.put("targetCluster", target.targetCluster());
        metadata.put("groupKey", target.groupKey());
        metadata.put("jobKey", jobKey);
        metadata.put("generation", expected.generation());
        metadata.put("manifestHash", codec.specHash(expected));
        metadata.put("files", files.stream().map(ArtifactFile::fileName).toList());
        Files.writeString(directory.resolve(METADATA_FILE), mapper.writeValueAsString(metadata),
                StandardCharsets.UTF_8);
        for (ArtifactFile file : files) {
            Files.writeString(directory.resolve(file.fileName()), file.definitionJson(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Validate an already materialized artifact without consulting mutable assignment rows.  This
     * is used before replacing a running job: the old artifact must remain available for rollback
     * after its job has been cancelled.
     */
    public RuntimeManifest verifyArtifact(DetectionArtifact artifact) {
        if (artifact == null) throw new IllegalArgumentException("artifact must not be null");
        try {
            Path directory = artifact.localPath().toAbsolutePath().normalize();
            if (!Files.isDirectory(directory)) {
                throw new IllegalStateException("artifact path is not a directory: " + directory);
            }
            byte[] rawManifest = Files.readAllBytes(directory.resolve(MANIFEST_FILE));
            if (!sha256(rawManifest).equalsIgnoreCase(artifact.manifestHash())) {
                throw new IllegalStateException("artifact manifest hash is corrupt");
            }
            RuntimeManifest manifest = codec.decode(decodeUtf8(rawManifest));
            if (manifest.generation() != artifact.generation()
                    || !codec.specHash(manifest).equalsIgnoreCase(artifact.manifestHash())
                    || manifest.jobKey() != null && !artifact.jobKey().equals(manifest.jobKey())) {
                throw new IllegalStateException("artifact manifest identity is invalid");
            }

            byte[] rawMetadata = Files.readAllBytes(directory.resolve(METADATA_FILE));
            JsonNode metadata = mapper.readTree(decodeUtf8(rawMetadata));
            if (metadata == null || !metadata.isObject()
                    || !value(metadata, "jobKey").equals(artifact.jobKey())
                    || !value(metadata, "generation").equals(Long.toString(artifact.generation()))
                    || !value(metadata, "manifestHash").equalsIgnoreCase(artifact.manifestHash())
                    || !value(metadata, "tenantId").equals(manifest.tenantId())
                    || !value(metadata, "targetCluster").equals(manifest.targetCluster())
                    || !value(metadata, "groupKey").equals(manifest.jobGroupKey())) {
                throw new IllegalStateException("artifact metadata identity is invalid");
            }
            JsonNode listed = metadata.get("files");
            if (listed == null || !listed.isArray() || listed.size() != manifest.members().size()) {
                throw new IllegalStateException("artifact metadata file list is invalid");
            }
            Set<String> expectedNames = new HashSet<>();
            expectedNames.add(MANIFEST_FILE);
            expectedNames.add(METADATA_FILE);
            for (JsonNode entry : listed) {
                if (!entry.isTextual() || entry.textValue().isBlank()
                        || !expectedNames.add(entry.textValue())) {
                    throw new IllegalStateException("artifact metadata file list is invalid");
                }
                Path file = directory.resolve(entry.textValue()).normalize();
                ensureChild(directory, file);
                if (!Files.isRegularFile(file)) {
                    throw new IllegalStateException("artifact definition is missing: " + entry.textValue());
                }
            }
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
                for (Path path : stream) {
                    if (!expectedNames.contains(path.getFileName().toString())) {
                        throw new IllegalStateException("unexpected file in immutable artifact: "
                                + path.getFileName());
                    }
                }
            }
            return manifest;
        } catch (IOException e) {
            throw new IllegalStateException("cannot verify detection artifact: " + artifact.localPath(), e);
        }
    }

    private void verifyExisting(DetectionArtifact artifact, RuntimeManifest expected,
                                String canonical, List<ArtifactFile> files) {
        try {
            if (!Files.isDirectory(artifact.localPath())) {
                throw new IllegalStateException("artifact path is not a directory: " + artifact.localPath());
            }
            byte[] raw = Files.readAllBytes(artifact.localPath().resolve(MANIFEST_FILE));
            if (!java.util.Arrays.equals(raw, canonical.getBytes(StandardCharsets.UTF_8))) {
                throw new IllegalStateException("artifact manifest bytes differ from canonical expected manifest");
            }
            if (!sha256(raw).equals(artifact.manifestHash())) {
                throw new IllegalStateException("artifact manifest hash is corrupt");
            }
            RuntimeManifest decoded = codec.decode(new String(raw, StandardCharsets.UTF_8));
            if (!codec.canonicalSpecJson(decoded).equals(canonical)) {
                throw new IllegalStateException("artifact manifest is not canonical");
            }
            JsonNode metadata = mapper.readTree(Files.readString(
                    artifact.localPath().resolve(METADATA_FILE), StandardCharsets.UTF_8));
            requireMetadata(metadata, expected, artifact, files);
            Set<String> expectedNames = new HashSet<>();
            expectedNames.add(MANIFEST_FILE);
            expectedNames.add(METADATA_FILE);
            for (ArtifactFile file : files) {
                expectedNames.add(file.fileName());
                Path path = artifact.localPath().resolve(file.fileName()).normalize();
                ensureChild(artifact.localPath(), path);
                if (!Files.isRegularFile(path)
                        || !java.util.Arrays.equals(Files.readAllBytes(path),
                        file.definitionJson().getBytes(StandardCharsets.UTF_8))) {
                    throw new IllegalStateException("artifact definition is corrupt: " + file.fileName());
                }
            }
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(artifact.localPath())) {
                for (Path path : stream) {
                    if (!expectedNames.contains(path.getFileName().toString())) {
                        throw new IllegalStateException("unexpected file in immutable artifact: " + path.getFileName());
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot verify detection artifact: " + artifact.localPath(), e);
        }
    }

    private void requireMetadata(JsonNode metadata, RuntimeManifest expected,
                                 DetectionArtifact artifact, List<ArtifactFile> files) {
        if (metadata == null || !metadata.isObject()
                || !value(metadata, "jobKey").equals(artifact.jobKey())
                || !value(metadata, "generation").equals(Long.toString(artifact.generation()))
                || !value(metadata, "manifestHash").equals(artifact.manifestHash())
                || !value(metadata, "tenantId").equals(expected.tenantId())
                || !value(metadata, "targetCluster").equals(expected.targetCluster())
                || !value(metadata, "groupKey").equals(expected.jobGroupKey())) {
            throw new IllegalStateException("artifact metadata does not match expected artifact");
        }
        JsonNode listed = metadata.get("files");
        List<String> names = files.stream().map(ArtifactFile::fileName).toList();
        if (listed == null || !listed.isArray() || listed.size() != names.size()) {
            throw new IllegalStateException("artifact metadata file list is invalid");
        }
        for (int i = 0; i < names.size(); i++) {
            if (!names.get(i).equals(listed.get(i).asText())) {
                throw new IllegalStateException("artifact metadata file list is invalid");
            }
        }
    }

    private static String value(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return "";
        return value.isTextual() ? value.textValue() : value.toString();
    }

    private String containerPath(String jobKey, long generation, String hash) {
        return containerArtifactRoot + "/" + jobKey + "/" + generation + "-" + hash;
    }

    private static Path safeResolve(Path root, String child) {
        Path result = root.resolve(child).normalize();
        ensureChild(root, result);
        return result;
    }

    private static void ensureChild(Path root, Path child) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedChild = child.toAbsolutePath().normalize();
        if (!normalizedChild.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException("artifact path escapes configured artifact root");
        }
    }

    private static String safeSlug(String ruleKey) {
        String slug = ruleKey.replaceAll("[^A-Za-z0-9._-]", "_");
        if (slug.isBlank()) slug = "rule";
        return slug.length() > 80 ? slug.substring(0, 80) : slug;
    }

    private static String requiredContainerRoot(String root) {
        if (root == null || root.isBlank() || !root.startsWith("/")) {
            throw new IllegalArgumentException("container artifact root must be an absolute path");
        }
        return root.replaceAll("/+$", "");
    }

    private static void moveNew(Path temporary, Path destination) throws IOException {
        Files.createDirectories(destination.getParent());
        try {
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, destination);
        }
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) {
            // The original materialization failure is more useful to the caller.
        }
    }

    private static String decodeUtf8(byte[] raw) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new IllegalStateException("artifact bytes are not valid UTF-8", e);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private record RuleRow(String ruleKey, long revision, java.util.UUID planId,
                           String planHash, long generation, String definitionJson) { }

    private record ArtifactFile(String fileName, String ruleKey, String definitionJson) { }
}
