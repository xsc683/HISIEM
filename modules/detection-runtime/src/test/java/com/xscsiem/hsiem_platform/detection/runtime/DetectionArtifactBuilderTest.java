package com.xscsiem.hsiem_platform.detection.runtime;

import com.xscsiem.hsiem_platform.rules.runtime.RuntimeManifest;
import com.xscsiem.hsiem_platform.rules.runtime.RuntimeManifestCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import com.zaxxer.hikari.HikariDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DetectionArtifactBuilderTest {

    @TempDir
    Path temp;

    JdbcTemplate jdbc;
    HikariDataSource dataSource;
    RuntimeManifestCodec codec;
    UUID revisionId;
    UUID planId;

    @BeforeEach
    void setUp() {
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl("jdbc:h2:mem:artifact-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE rule_revision (revision_id UUID, rule_key VARCHAR(128), revision BIGINT, definition_json TEXT)");
        jdbc.execute("CREATE TABLE detection_plan (plan_id UUID, revision_id UUID, plan_hash VARCHAR(128))");
        jdbc.execute("CREATE TABLE rule_job_assignment (tenant_id VARCHAR(64), rule_key VARCHAR(128), revision BIGINT, plan_id UUID, plan_hash VARCHAR(128), group_key VARCHAR(512), generation BIGINT)");
        codec = new RuntimeManifestCodec();
        revisionId = UUID.randomUUID();
        planId = UUID.randomUUID();
        jdbc.update("INSERT INTO rule_revision VALUES (?, ?, ?, ?)", revisionId, "../../evil", 7L,
                "{\"id\":\"../../evil\",\"enabled\":true,\"category\":\"single_event\"}");
        jdbc.update("INSERT INTO detection_plan VALUES (?, ?, ?)", planId, revisionId, "plan-hash");
        jdbc.update("INSERT INTO rule_job_assignment VALUES (?, ?, ?, ?, ?, ?, ?)",
                "tenant-a", "../../evil", 7L, planId, "plan-hash", "group-a", 4L);
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void materializesCanonicalManifestAndReusesImmutableDirectory() throws Exception {
        DetectionRuntimeTarget target = target("group-a", 4L);
        DetectionArtifactBuilder builder = new DetectionArtifactBuilder(jdbc, codec, temp,
                "/opt/flink/detection-artifacts");

        DetectionArtifact first = builder.build(target);
        DetectionArtifact second = builder.build(target);

        assertEquals(first.localPath(), second.localPath());
        assertTrue(first.localPath().startsWith(temp.toAbsolutePath()));
        assertEquals(codec.canonicalSpecJson(codec.decode(Files.readString(
                first.localPath().resolve("runtime-manifest.json")))),
                Files.readString(first.localPath().resolve("runtime-manifest.json")));
        assertTrue(Files.exists(first.localPath().resolve("artifact-metadata.json")));
        try (var files = Files.list(first.localPath())) {
            assertTrue(files.map(path -> path.getFileName().toString())
                    .anyMatch(name -> name.matches("0001-[A-Za-z0-9._-]+\\.yaml")));
        }
        assertFalse(first.localPath().toString().contains("..\\evil"));
    }

    @Test
    void runtimeArtifactForcesDisabledRevisionDefinitionEnabled() throws Exception {
        jdbc.update("UPDATE rule_revision SET definition_json = ? WHERE revision_id = ?",
                "{\"z\":\"last\",\"enabled\":false,\"id\":\"../../evil\",\"category\":\"single_event\"}",
                revisionId);
        DetectionRuntimeTarget target = target("group-a", 4L);

        DetectionArtifact artifact = new DetectionArtifactBuilder(jdbc, codec, temp).build(target);
        Path definitionPath;
        try (var files = Files.list(artifact.localPath())) {
            definitionPath = files.filter(path -> path.getFileName().toString().endsWith(".yaml"))
                    .findFirst().orElseThrow();
        }
        String definition = Files.readString(definitionPath);

        assertTrue(definition.contains("\"enabled\":true"));
        assertFalse(definition.contains("\"enabled\":false"));
        assertTrue(definition.indexOf("\"category\"") < definition.indexOf("\"enabled\""));
    }

    @Test
    void materializesTwoRuleGroupAfterOneMemberRevisionChange() throws Exception {
        UUID secondRevisionId = UUID.randomUUID();
        UUID secondPlanId = UUID.randomUUID();
        jdbc.update("INSERT INTO rule_revision VALUES (?, ?, ?, ?)", secondRevisionId, "rule-b", 2L,
                "{\"id\":\"rule-b\",\"enabled\":true,\"category\":\"single_event\"}");
        jdbc.update("INSERT INTO detection_plan VALUES (?, ?, ?)", secondPlanId, secondRevisionId, "plan-b");
        jdbc.update("INSERT INTO rule_job_assignment VALUES (?, ?, ?, ?, ?, ?, ?)",
                "tenant-a", "rule-b", 2L, secondPlanId, "plan-b", "group-a", 4L);
        RuntimeManifest manifest = new RuntimeManifest(RuntimeManifest.SCHEMA_VERSION, "tenant-a", "default",
                "group-a", 4L, List.of(
                new RuntimeManifest.Member("../../evil", 7L, "plan-hash"),
                new RuntimeManifest.Member("rule-b", 2L, "plan-b")));
        DetectionRuntimeTarget target = new DetectionRuntimeTarget("tenant-a", "group-a", "default", 4L,
                codec.canonicalSpecJson(manifest), codec.specHash(manifest));

        DetectionArtifact artifact = new DetectionArtifactBuilder(jdbc, codec, temp).build(target);

        try (var files = Files.list(artifact.localPath())) {
            assertEquals(2, files.filter(path -> path.getFileName().toString().endsWith(".yaml")).count());
        }
    }

    @Test
    void rejectsAssignmentMismatchAndCorruptReuse() throws Exception {
        DetectionRuntimeTarget target = target("group-a", 4L);
        DetectionArtifactBuilder builder = new DetectionArtifactBuilder(jdbc, codec, temp);
        builder.build(target);
        jdbc.update("UPDATE rule_job_assignment SET plan_hash = ?", "different");
        assertThrows(RuntimeException.class, () -> builder.build(target));

        // A separate builder/database row is not needed: restoring the assignment and corrupting
        // the immutable bytes must still be rejected on a later reuse attempt.
        jdbc.update("UPDATE rule_job_assignment SET plan_hash = ?", "plan-hash");
        Path manifest = builder.artifactPath(
                new DetectionJobNameCodec().jobKey("tenant-a", "default", "group-a"), 4L,
                codec.specHash(codec.decode(target.expectedManifestJson())));
        Files.writeString(manifest.resolve("runtime-manifest.json"), "{}", StandardCharsets.UTF_8);
        assertThrows(RuntimeException.class, () -> builder.build(target));
    }

    @Test
    void rejectsEmptyExpectedManifest() {
        RuntimeManifest empty = new RuntimeManifest(RuntimeManifest.SCHEMA_VERSION, "tenant-a", "default",
                "group-a", 4L, List.of());
        DetectionRuntimeTarget target = new DetectionRuntimeTarget("tenant-a", "group-a", "default", 4L,
                codec.canonicalSpecJson(empty), codec.specHash(empty));
        assertThrows(IllegalArgumentException.class,
                () -> new DetectionArtifactBuilder(jdbc, codec, temp).build(target));
    }

    private DetectionRuntimeTarget target(String group, long generation) {
        RuntimeManifest manifest = new RuntimeManifest(RuntimeManifest.SCHEMA_VERSION, "tenant-a", "default",
                group, generation, List.of(new RuntimeManifest.Member("../../evil", 7L, "plan-hash")));
        return new DetectionRuntimeTarget("tenant-a", group, "default", generation,
                codec.canonicalSpecJson(manifest), codec.specHash(manifest));
    }
}
