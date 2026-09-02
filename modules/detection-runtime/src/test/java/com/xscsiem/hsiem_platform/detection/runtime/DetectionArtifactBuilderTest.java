package com.xscsiem.hsiem_platform.detection.runtime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xscsiem.hsiem_platform.rules.DetectionPlanCompiler;
import com.xscsiem.hsiem_platform.rules.runtime.RuntimeManifest;
import com.xscsiem.hsiem_platform.rules.runtime.RuntimeManifestCodec;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

class DetectionArtifactBuilderTest {

    private static final String RULE_KEY = "rule-a";
    private static final String PLAN_JSON =
            "{\"alert\":{\"description\":\"canonical fixture\",\"name\":\"rule-a detection\",\"risk_score\":75,\"severity\":\"high\",\"status\":\"stable\",\"tags\":[\"runtime\",\"contract\"],\"type\":\"test_detection\",\"version\":\"1.0\"},\"compiler_version\":\"hisiem-detection-plan-2\",\"detection\":{\"condition\":{\"field\":\"event.action\",\"operator\":\"eq\",\"value\":\"login\"},\"suppression\":{\"duration_minutes\":60,\"emission\":\"first_and_final_count\",\"fallback_entity\":\"unknown\",\"fallback_entity_fields\":[\"source.ip\",\"user.name\"],\"primary_entity_field\":null,\"time_basis\":\"processing_time\"},\"type\":\"single_event\"},\"input\":{\"source\":\"siem-events\"},\"rule_key\":\"rule-a\",\"schema_version\":\"2\"}";
    private static final String PLAN_HASH =
            "f9796d6e28d8cff1f3bdc06a00118a255e056c0eadb7c57436ba5df4aa2b0021";
    private static final String PLAN_B_JSON =
            PLAN_JSON
                    .replace("rule-a detection", "rule-b detection")
                    .replace("\"rule_key\":\"rule-a\"", "\"rule_key\":\"rule-b\"");
    private static final String PLAN_B_HASH = sha256(PLAN_B_JSON);

    @TempDir Path temp;

    JdbcTemplate jdbc;
    HikariDataSource dataSource;
    RuntimeManifestCodec codec;
    UUID revisionId;
    UUID planId;

    @BeforeEach
    void setUp() {
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(
                "jdbc:h2:mem:artifact-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute(
                "CREATE TABLE rule_revision (revision_id UUID, rule_key VARCHAR(128), revision BIGINT, definition_json TEXT)");
        jdbc.execute(
                "CREATE TABLE detection_plan (plan_id UUID, revision_id UUID, compiler_version VARCHAR(128), plan_json TEXT, plan_hash VARCHAR(128))");
        jdbc.execute(
                "CREATE TABLE rule_job_assignment (tenant_id VARCHAR(64), rule_key VARCHAR(128), revision BIGINT, plan_id UUID, plan_hash VARCHAR(128), group_key VARCHAR(512), generation BIGINT)");
        codec = new RuntimeManifestCodec();
        revisionId = UUID.randomUUID();
        planId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO rule_revision VALUES (?, ?, ?, ?)",
                revisionId,
                RULE_KEY,
                7L,
                "{\"id\":\"rule-a\",\"category\":\"not_authoritative\"}");
        jdbc.update(
                "INSERT INTO detection_plan VALUES (?, ?, ?, ?, ?)",
                planId,
                revisionId,
                DetectionPlanCompiler.VERSION,
                PLAN_JSON,
                PLAN_HASH);
        jdbc.update(
                "INSERT INTO rule_job_assignment VALUES (?, ?, ?, ?, ?, ?, ?)",
                "tenant-a",
                RULE_KEY,
                7L,
                planId,
                PLAN_HASH,
                "group-a",
                4L);
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void materializesFromStoredPlanAndReusesImmutableDirectory() throws Exception {
        assertEquals(PLAN_HASH, sha256(PLAN_JSON));
        DetectionRuntimeTarget target = target("group-a", 4L);
        DetectionArtifactBuilder builder =
                new DetectionArtifactBuilder(
                        new DetectionArtifactRepository(jdbc),
                        codec,
                        temp,
                        "/opt/flink/detection-artifacts");

        DetectionArtifact first = builder.build(target);
        byte[] manifestBytes =
                Files.readAllBytes(first.localPath().resolve("runtime-manifest.json"));
        byte[] metadataBytes =
                Files.readAllBytes(first.localPath().resolve("artifact-metadata.json"));
        byte[] definitionBytes = Files.readAllBytes(first.localPath().resolve("0001-rule-a.yaml"));
        DetectionArtifact second = builder.build(target);

        assertEquals(first.localPath(), second.localPath());
        assertTrue(first.localPath().startsWith(temp.toAbsolutePath()));
        assertEquals(
                codec.canonicalSpecJson(
                        codec.decode(
                                Files.readString(
                                        first.localPath().resolve("runtime-manifest.json")))),
                Files.readString(first.localPath().resolve("runtime-manifest.json")));
        assertTrue(Files.exists(first.localPath().resolve("artifact-metadata.json")));
        assertEquals(
                new FlinkArtifactCompiler().compile(PLAN_JSON, DetectionPlanCompiler.VERSION),
                Files.readString(first.localPath().resolve("0001-rule-a.yaml")));
        assertArrayEquals(
                manifestBytes,
                Files.readAllBytes(second.localPath().resolve("runtime-manifest.json")));
        assertArrayEquals(
                metadataBytes,
                Files.readAllBytes(second.localPath().resolve("artifact-metadata.json")));
        assertArrayEquals(
                definitionBytes,
                Files.readAllBytes(second.localPath().resolve("0001-rule-a.yaml")));
    }

    @Test
    void changingOrCorruptingRevisionDefinitionDoesNotChangeArtifactBytes() throws Exception {
        DetectionRuntimeTarget target = target("group-a", 4L);
        DetectionArtifactBuilder builder =
                new DetectionArtifactBuilder(new DetectionArtifactRepository(jdbc), codec, temp);
        DetectionArtifact first = builder.build(target);
        byte[] manifestBytes =
                Files.readAllBytes(first.localPath().resolve("runtime-manifest.json"));
        byte[] metadataBytes =
                Files.readAllBytes(first.localPath().resolve("artifact-metadata.json"));
        byte[] definitionBytes = Files.readAllBytes(first.localPath().resolve("0001-rule-a.yaml"));

        jdbc.update(
                "UPDATE rule_revision SET definition_json = ? WHERE revision_id = ?",
                "not-json-at-all",
                revisionId);
        DetectionArtifact second = builder.build(target);

        assertEquals(first.localPath(), second.localPath());
        assertArrayEquals(
                manifestBytes,
                Files.readAllBytes(second.localPath().resolve("runtime-manifest.json")));
        assertArrayEquals(
                metadataBytes,
                Files.readAllBytes(second.localPath().resolve("artifact-metadata.json")));
        assertArrayEquals(
                definitionBytes,
                Files.readAllBytes(second.localPath().resolve("0001-rule-a.yaml")));
    }

    @Test
    void materializesTwoRuleGroupFromBothStoredPlans() throws Exception {
        UUID secondRevisionId = UUID.randomUUID();
        UUID secondPlanId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO rule_revision VALUES (?, ?, ?, ?)",
                secondRevisionId,
                "rule-b",
                2L,
                "corrupt revision content");
        jdbc.update(
                "INSERT INTO detection_plan VALUES (?, ?, ?, ?, ?)",
                secondPlanId,
                secondRevisionId,
                DetectionPlanCompiler.VERSION,
                PLAN_B_JSON,
                PLAN_B_HASH);
        jdbc.update(
                "INSERT INTO rule_job_assignment VALUES (?, ?, ?, ?, ?, ?, ?)",
                "tenant-a",
                "rule-b",
                2L,
                secondPlanId,
                PLAN_B_HASH,
                "group-a",
                4L);
        RuntimeManifest manifest =
                new RuntimeManifest(
                        RuntimeManifest.SCHEMA_VERSION,
                        "tenant-a",
                        "default",
                        "group-a",
                        4L,
                        List.of(
                                new RuntimeManifest.Member(RULE_KEY, 7L, PLAN_HASH),
                                new RuntimeManifest.Member("rule-b", 2L, PLAN_B_HASH)));
        DetectionRuntimeTarget target =
                new DetectionRuntimeTarget(
                        "tenant-a",
                        "group-a",
                        "default",
                        4L,
                        codec.canonicalSpecJson(manifest),
                        codec.specHash(manifest));

        DetectionArtifact artifact =
                new DetectionArtifactBuilder(new DetectionArtifactRepository(jdbc), codec, temp)
                        .build(target);

        try (var files = Files.list(artifact.localPath())) {
            assertEquals(
                    2,
                    files.filter(path -> path.getFileName().toString().endsWith(".yaml")).count());
        }
    }

    @Test
    void rejectsStoredPlanHashMismatch() {
        String mismatchedHash = "0".repeat(64);
        jdbc.update(
                "UPDATE detection_plan SET plan_hash = ? WHERE plan_id = ?",
                mismatchedHash,
                planId);
        jdbc.update(
                "UPDATE rule_job_assignment SET plan_hash = ? WHERE plan_id = ?",
                mismatchedHash,
                planId);

        assertThrows(
                IllegalStateException.class,
                () ->
                        new DetectionArtifactBuilder(
                                        new DetectionArtifactRepository(jdbc), codec, temp)
                                .build(target("group-a", 4L)));
    }

    @Test
    void rejectsMalformedAndIncompletePlanJsonThroughBackendCompilation() {
        String malformedHash = storePlan("{");
        IllegalArgumentException malformed =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                new DetectionArtifactBuilder(
                                                new DetectionArtifactRepository(jdbc), codec, temp)
                                        .build(target("group-a", 4L, malformedHash)));
        assertTrue(malformed.getMessage().contains("invalid detection plan JSON"));

        String incompletePlan = PLAN_JSON.replace("\"severity\":\"high\",", "");
        String incompleteHash = storePlan(incompletePlan);
        IllegalArgumentException incomplete =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                new DetectionArtifactBuilder(
                                                new DetectionArtifactRepository(jdbc), codec, temp)
                                        .build(target("group-a", 4L, incompleteHash)));
        assertTrue(incomplete.getMessage().contains("alert is missing field: severity"));
    }

    @Test
    void rejectsAssignmentMismatchAndOnDiskCorruption() throws Exception {
        DetectionRuntimeTarget target = target("group-a", 4L);
        DetectionArtifactBuilder builder =
                new DetectionArtifactBuilder(new DetectionArtifactRepository(jdbc), codec, temp);
        builder.build(target);
        jdbc.update("UPDATE rule_job_assignment SET plan_hash = ?", "different");
        assertThrows(RuntimeException.class, () -> builder.build(target));

        jdbc.update("UPDATE rule_job_assignment SET plan_hash = ?", PLAN_HASH);
        Path manifest =
                builder.artifactPath(
                        new DetectionJobNameCodec().jobKey("tenant-a", "default", "group-a"),
                        4L,
                        codec.specHash(codec.decode(target.expectedManifestJson())));
        Files.writeString(manifest.resolve("runtime-manifest.json"), "{}", StandardCharsets.UTF_8);
        assertThrows(RuntimeException.class, () -> builder.build(target));
    }

    @Test
    void rejectsEmptyExpectedManifest() {
        RuntimeManifest empty =
                new RuntimeManifest(
                        RuntimeManifest.SCHEMA_VERSION,
                        "tenant-a",
                        "default",
                        "group-a",
                        4L,
                        List.of());
        DetectionRuntimeTarget target =
                new DetectionRuntimeTarget(
                        "tenant-a",
                        "group-a",
                        "default",
                        4L,
                        codec.canonicalSpecJson(empty),
                        codec.specHash(empty));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new DetectionArtifactBuilder(
                                        new DetectionArtifactRepository(jdbc), codec, temp)
                                .build(target));
    }

    private DetectionRuntimeTarget target(String group, long generation) {
        return target(group, generation, PLAN_HASH);
    }

    private DetectionRuntimeTarget target(String group, long generation, String planHash) {
        RuntimeManifest manifest =
                new RuntimeManifest(
                        RuntimeManifest.SCHEMA_VERSION,
                        "tenant-a",
                        "default",
                        group,
                        generation,
                        List.of(new RuntimeManifest.Member(RULE_KEY, 7L, planHash)));
        return new DetectionRuntimeTarget(
                "tenant-a",
                group,
                "default",
                generation,
                codec.canonicalSpecJson(manifest),
                codec.specHash(manifest));
    }

    private String storePlan(String planJson) {
        String planHash = sha256(planJson);
        jdbc.update(
                "UPDATE detection_plan SET plan_json = ?, plan_hash = ? WHERE plan_id = ?",
                planJson,
                planHash,
                planId);
        jdbc.update(
                "UPDATE rule_job_assignment SET plan_hash = ? WHERE plan_id = ?", planHash, planId);
        return planHash;
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
