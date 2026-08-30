package com.xscsiem.hsiem_platform.rules.runtime;

import com.xscsiem.hsiem_platform.rules.ManagedDetectionService;
import com.xscsiem.hsiem_platform.rules.RuleService;
import com.xscsiem.hsiem_platform.tenant.TenantContext;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import com.zaxxer.hikari.HikariDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DetectionRuntimeServiceTest {

    @TempDir
    Path rulesDir;

    private HikariDataSource dataSource;

    @AfterEach
    void close() {
        TenantContext.clear();
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void deployPersistsGroupAssignmentAndExpectedManifestWithoutFakingObservation() throws Exception {
        Files.writeString(rulesDir.resolve("rule-runtime-test.yaml"), rule("runtime test", "login"));
        JdbcTemplate jdbc = jdbc();
        ManagedDetectionService managed = managed(jdbc);
        TenantContext.set("default");

        Map<String, Object> deployment = managed.deploy("default", "rule-runtime-test",
                Map.of("targetCluster", "cluster-a"), "tester");
        assertEquals("RUNNING", deployment.get("desired_state"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM detection_job_group", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM rule_job_assignment", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM rule_runtime_status", Integer.class));
        assertNotNull(jdbc.queryForObject("SELECT plan_id FROM rule_job_assignment", Object.class));
        assertEquals(jdbc.queryForObject("SELECT plan_id FROM detection_plan", Object.class),
                jdbc.queryForObject("SELECT plan_id FROM rule_job_assignment", Object.class));

        Map<String, Object> group = jdbc.queryForMap("SELECT * FROM detection_job_group");
        RuntimeManifest expected = new RuntimeManifestCodec()
                .decode((String) group.get("expected_manifest_json"));
        assertEquals("default", expected.tenantId());
        assertEquals("cluster-a", expected.targetCluster());
        assertEquals(1L, expected.generation());
        assertEquals(List.of("rule-runtime-test"), expected.members().stream()
                .map(RuntimeManifest.Member::ruleKey).toList());
        assertEquals(new RuntimeManifestCodec().specHash(expected), group.get("expected_manifest_hash"));
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT runtime_state FROM rule_runtime_status WHERE tenant_id = 'default' "
                        + "AND rule_key = 'rule-runtime-test'", String.class));
    }

    @Test
    void matchingObservationRunsAndMismatchVariantsRemainTyped() throws Exception {
        Files.writeString(rulesDir.resolve("rule-runtime-test.yaml"), rule("runtime test", "login"));
        JdbcTemplate jdbc = jdbc();
        ManagedDetectionService managed = managed(jdbc);
        DetectionRuntimeService runtime = new DetectionRuntimeService(jdbc, 3);
        TenantContext.set("default");
        managed.deploy("default", "rule-runtime-test", Map.of(), "tester");

        RuntimeManifest expected = expected(jdbc);
        RuntimeManifest matching = withMembers(expected, expected.generation(),
                List.of(new RuntimeManifest.Member("rule-runtime-test", 1, planHash(expected))));
        RuntimeDiff matchingDiff = runtime.observe(matching, RuntimeJobState.RUNNING);
        assertTrue(matchingDiff.isEmpty());
        assertEquals("RUNNING", jdbc.queryForObject(
                "SELECT runtime_state FROM rule_runtime_status", String.class));
        assertEquals("RUNNING", jdbc.queryForObject(
                "SELECT status FROM detection_job_group", String.class));

        RuntimeManifest revisionMismatch = withMembers(expected, expected.generation(),
                List.of(new RuntimeManifest.Member("rule-runtime-test", 2, planHash(expected))));
        RuntimeDiff revisionDiff = runtime.observe(revisionMismatch, RuntimeJobState.RUNNING);
        assertEquals(List.of("rule-runtime-test"), revisionDiff.outdatedRuleKeys());
        assertTrue(revisionDiff.outdated().getFirst().revisionMismatch());
        assertEquals("OUT_OF_SYNC", jdbc.queryForObject(
                "SELECT runtime_state FROM rule_runtime_status", String.class));

        RuntimeManifest planMismatch = withMembers(expected, expected.generation(),
                List.of(new RuntimeManifest.Member("rule-runtime-test", 1, "different-plan")));
        RuntimeDiff planDiff = runtime.observe(planMismatch, RuntimeJobState.RUNNING);
        assertTrue(planDiff.outdated().getFirst().planHashMismatch());

        RuntimeManifest generationMismatch = withMembers(expected, expected.generation() + 1,
                List.of(new RuntimeManifest.Member("rule-runtime-test", 1, planHash(expected))));
        RuntimeDiff generationDiff = runtime.observe(generationMismatch, RuntimeJobState.RUNNING);
        assertTrue(generationDiff.generationMismatch());
        assertTrue(generationDiff.outdated().getFirst().generationMismatch());

        RuntimeManifest missingAndUnexpected = withMembers(expected, expected.generation(),
                List.of(new RuntimeManifest.Member("other-rule", 1, "other-plan")));
        RuntimeDiff memberDiff = runtime.observe(missingAndUnexpected, RuntimeJobState.RUNNING);
        assertEquals(List.of("rule-runtime-test"), memberDiff.missingRuleKeys());
        assertEquals(List.of("other-rule"), memberDiff.unexpectedRuleKeys());
        assertFalse(memberDiff.isEmpty());
    }

    @Test
    void stoppedConvergesToDisabledAndFailedOrUnknownAreNotHealthy() throws Exception {
        Files.writeString(rulesDir.resolve("rule-runtime-test.yaml"), rule("runtime test", "login"));
        JdbcTemplate jdbc = jdbc();
        ManagedDetectionService managed = managed(jdbc);
        DetectionRuntimeService runtime = new DetectionRuntimeService(jdbc, 1);
        TenantContext.set("default");
        managed.deploy("default", "rule-runtime-test", Map.of(), "tester");
        RuntimeManifest expected = expected(jdbc);

        runtime.observe(expected, RuntimeJobState.FAILED, "JOB_FAILED", "job failed");
        assertEquals("FAILED", jdbc.queryForObject(
                "SELECT runtime_state FROM rule_runtime_status", String.class));
        runtime.observe(expected, RuntimeJobState.UNKNOWN);
        assertEquals("UNKNOWN", jdbc.queryForObject(
                "SELECT runtime_state FROM rule_runtime_status", String.class));

        managed.stop("default", "rule-runtime-test", "tester");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM rule_job_assignment", Integer.class));
        Map<String, Object> stoppedGroup = jdbc.queryForMap("SELECT * FROM detection_job_group");
        RuntimeManifest stoppedExpected = new RuntimeManifestCodec()
                .decode((String) stoppedGroup.get("expected_manifest_json"));
        assertTrue(stoppedExpected.members().isEmpty());
        runtime.observe(stoppedExpected, RuntimeJobState.STOPPED);
        assertEquals("DISABLED", jdbc.queryForObject(
                "SELECT runtime_state FROM rule_runtime_status", String.class));
        assertEquals("DISABLED", jdbc.queryForObject("SELECT status FROM rule_deployment", String.class));
    }

    @Test
    void changingOneRuleLeavesAnotherGroupAssignmentAndObservedJobUntouched() throws Exception {
        JdbcTemplate jdbc = jdbc();
        DetectionRuntimeService runtime = new DetectionRuntimeService(jdbc, 2);
        String firstRule = "rule-runtime-a";
        String secondRule = List.of("rule-runtime-b", "rule-runtime-c", "rule-runtime-d").stream()
                .filter(candidate -> runtime.bucket("default", "siem-events", "single_event", firstRule)
                        != runtime.bucket("default", "siem-events", "single_event", candidate))
                .findFirst().orElseThrow();
        Files.writeString(rulesDir.resolve(firstRule + ".yaml"),
                rule(firstRule, "first rule", "login"));
        Files.writeString(rulesDir.resolve(secondRule + ".yaml"),
                rule(secondRule, "second rule", "logout"));
        assertNotEquals(runtime.groupKey("default", "cluster-a", "siem-events", "single_event", firstRule),
                runtime.groupKey("default", "cluster-a", "siem-events", "single_event", secondRule));

        ManagedDetectionService managed = new ManagedDetectionService(jdbc,
                new RuleService(rulesDir.toString(), "http://localhost:9200"), "test-commit", runtime);
        managed.deploy("default", firstRule, Map.of("targetCluster", "cluster-a"), "tester");
        managed.deploy("default", secondRule, Map.of("targetCluster", "cluster-a"), "tester");
        String secondGroup = jdbc.queryForObject("""
                SELECT group_key FROM rule_job_assignment WHERE rule_key = ?
                """, String.class, secondRule);
        RuntimeManifest expectedSecond = new RuntimeManifestCodec().decode(jdbc.queryForObject("""
                SELECT expected_manifest_json FROM detection_job_group WHERE group_key = ?
                """, String.class, secondGroup));
        runtime.observe(new RuntimeManifest(expectedSecond.schemaVersion(), expectedSecond.tenantId(),
                expectedSecond.targetCluster(), expectedSecond.jobGroupKey(), expectedSecond.generation(),
                "job-second", "key-second", expectedSecond.members()), RuntimeJobState.RUNNING);

        Map<String, Object> assignmentBefore = jdbc.queryForMap("""
                SELECT deployment_id, revision, plan_id, plan_hash, group_key, generation
                FROM rule_job_assignment WHERE rule_key = ?
                """, secondRule);
        Map<String, Object> statusBefore = jdbc.queryForMap("""
                SELECT group_key, target_cluster, job_id, job_key, observed_revision,
                       observed_generation, observed_plan_hash, runtime_state
                FROM rule_runtime_status WHERE rule_key = ?
                """, secondRule);
        Map<String, Object> groupBefore = jdbc.queryForMap("""
                SELECT desired_generation, expected_manifest_json, expected_manifest_hash,
                       status, job_id, job_key, last_error
                FROM detection_job_group WHERE group_key = ?
                """, secondGroup);

        Files.writeString(rulesDir.resolve(firstRule + ".yaml"),
                rule(firstRule, "first rule changed", "password"));
        managed.deploy("default", firstRule, Map.of("targetCluster", "cluster-a"), "tester");

        Map<String, Object> assignmentAfter = jdbc.queryForMap("""
                SELECT deployment_id, revision, plan_id, plan_hash, group_key, generation
                FROM rule_job_assignment WHERE rule_key = ?
                """, secondRule);
        Map<String, Object> statusAfter = jdbc.queryForMap("""
                SELECT group_key, target_cluster, job_id, job_key, observed_revision,
                       observed_generation, observed_plan_hash, runtime_state
                FROM rule_runtime_status WHERE rule_key = ?
                """, secondRule);
        Map<String, Object> groupAfter = jdbc.queryForMap("""
                SELECT desired_generation, expected_manifest_json, expected_manifest_hash,
                       status, job_id, job_key, last_error
                FROM detection_job_group WHERE group_key = ?
                """, secondGroup);
        assertEquals(assignmentBefore, assignmentAfter);
        assertEquals(statusBefore, statusAfter);
        assertEquals(groupBefore, groupAfter);
    }

    @Test
    void changingOneRuleInSameGroupUpdatesEveryAssignmentForNextArtifact() throws Exception {
        String firstRule = "rule-same-group-a";
        String secondRule = "rule-same-group-b";
        Files.writeString(rulesDir.resolve(firstRule + ".yaml"),
                rule(firstRule, "first", "login"));
        Files.writeString(rulesDir.resolve(secondRule + ".yaml"),
                rule(secondRule, "second", "logout"));
        JdbcTemplate jdbc = jdbc();
        DetectionRuntimeService runtime = new DetectionRuntimeService(jdbc, 1);
        ManagedDetectionService managed = new ManagedDetectionService(jdbc,
                new RuleService(rulesDir.toString(), "http://localhost:9200"), "test-commit", runtime);
        TenantContext.set("default");

        managed.deploy("default", firstRule, Map.of("targetCluster", "cluster-a"), "tester");
        managed.deploy("default", secondRule, Map.of("targetCluster", "cluster-a"), "tester");
        String group = jdbc.queryForObject("SELECT group_key FROM rule_job_assignment WHERE rule_key = ?",
                String.class, firstRule);
        long before = jdbc.queryForObject("SELECT desired_generation FROM detection_job_group WHERE group_key = ?",
                Long.class, group);

        Files.writeString(rulesDir.resolve(firstRule + ".yaml"),
                rule(firstRule, "first changed", "password"));
        managed.deploy("default", firstRule, Map.of("targetCluster", "cluster-a"), "tester");

        long after = jdbc.queryForObject("SELECT desired_generation FROM detection_job_group WHERE group_key = ?",
                Long.class, group);
        assertTrue(after > before);
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM rule_job_assignment WHERE group_key = ?",
                Integer.class, group));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM rule_job_assignment "
                + "WHERE group_key = ? AND generation = ?", Integer.class, group, after));
    }
    @Test
    void inspectionUsesNewestObservedJobForRule() throws Exception {
        Files.writeString(rulesDir.resolve("rule-runtime-test.yaml"), rule("runtime test", "login"));
        JdbcTemplate jdbc = jdbc();
        ManagedDetectionService managed = managed(jdbc);
        DetectionRuntimeService runtime = new DetectionRuntimeService(jdbc, 1);
        TenantContext.set("default");
        managed.deploy("default", "rule-runtime-test", Map.of(), "tester");
        RuntimeManifest expected = expected(jdbc);
        runtime.observe(new RuntimeManifest(expected.schemaVersion(), expected.tenantId(),
                expected.targetCluster(), expected.jobGroupKey(), expected.generation(),
                "job-old", "key-old", expected.members()), RuntimeJobState.RUNNING);
        runtime.observe(new RuntimeManifest(expected.schemaVersion(), expected.tenantId(),
                expected.targetCluster(), expected.jobGroupKey(), expected.generation(),
                "job-new", "key-new", expected.members()), RuntimeJobState.RUNNING);

        Map<String, Object> inspected = runtime.inspect("default", "rule-runtime-test");
        @SuppressWarnings("unchecked")
        Map<String, Object> observed = (Map<String, Object>)
                ((Map<String, Object>) inspected.get("desiredVsObserved")).get("observed");
        assertEquals("job-new", observed.get("jobId"));
        assertEquals("key-new", observed.get("jobKey"));
    }

    @Test
    void tenantAndClusterScopesDoNotCrossDuringObservation() throws Exception {
        Files.writeString(rulesDir.resolve("rule-runtime-test.yaml"), rule("runtime test", "login"));
        JdbcTemplate jdbc = jdbc();
        jdbc.update("INSERT INTO tenants(id, name, created_by) VALUES ('tenant-a', 'A', 'test')");
        jdbc.update("INSERT INTO tenants(id, name, created_by) VALUES ('tenant-b', 'B', 'test')");
        ManagedDetectionService managed = managed(jdbc);
        DetectionRuntimeService runtime = new DetectionRuntimeService(jdbc, 1);

        managed.deploy("tenant-a", "rule-runtime-test", Map.of("targetCluster", "cluster-a"), "tester");
        managed.deploy("tenant-b", "rule-runtime-test", Map.of("targetCluster", "cluster-b"), "tester");
        Map<String, Object> groupA = jdbc.queryForMap("""
                SELECT * FROM detection_job_group WHERE tenant_id = 'tenant-a'
                """);
        RuntimeManifest expectedA = new RuntimeManifestCodec()
                .decode((String) groupA.get("expected_manifest_json"));
        runtime.observe(expectedA, RuntimeJobState.RUNNING);

        assertEquals("RUNNING", jdbc.queryForObject("""
                SELECT runtime_state FROM rule_runtime_status
                WHERE tenant_id = 'tenant-a' AND rule_key = 'rule-runtime-test'
                """, String.class));
        assertEquals("PENDING", jdbc.queryForObject("""
                SELECT runtime_state FROM rule_runtime_status
                WHERE tenant_id = 'tenant-b' AND rule_key = 'rule-runtime-test'
                """, String.class));
        assertEquals("PENDING", jdbc.queryForObject("""
                SELECT status FROM detection_job_group
                WHERE tenant_id = 'tenant-b' AND target_cluster = 'cluster-b'
                """, String.class));
    }

    @Test
    void rollbackPinsRevisionAndAdvancesGroupGeneration() throws Exception {
        Path file = rulesDir.resolve("rule-runtime-test.yaml");
        Files.writeString(file, rule("runtime test", "login"));
        JdbcTemplate jdbc = jdbc();
        ManagedDetectionService managed = managed(jdbc);
        TenantContext.set("default");
        Map<String, Object> first = managed.inspect("rule-runtime-test", "tester");
        @SuppressWarnings("unchecked")
        Map<String, Object> firstRevision = (Map<String, Object>) first.get("revision");
        managed.deploy("default", "rule-runtime-test", Map.of(), "tester");
        Files.writeString(file, rule("runtime test changed", "logout"));
        Map<String, Object> second = managed.inspect("rule-runtime-test", "tester");
        @SuppressWarnings("unchecked")
        Map<String, Object> secondRevision = (Map<String, Object>) second.get("revision");
        managed.deploy("default", "rule-runtime-test", Map.of("revisionId", secondRevision.get("revisionId")), "tester");
        long before = ((Number) jdbc.queryForObject(
                "SELECT desired_generation FROM detection_job_group", Long.class)).longValue();

        managed.rollback("default", "rule-runtime-test",
                Map.of("revisionId", firstRevision.get("revisionId")), "tester");
        long after = ((Number) jdbc.queryForObject(
                "SELECT desired_generation FROM detection_job_group", Long.class)).longValue();
        assertTrue(after > before);
        assertEquals(1L, jdbc.queryForObject("SELECT revision FROM rule_job_assignment", Long.class));
        assertNotNull(jdbc.queryForObject("SELECT expected_manifest_hash FROM detection_job_group", String.class));
    }

    private ManagedDetectionService managed(JdbcTemplate jdbc) {
        return new ManagedDetectionService(jdbc, new RuleService(rulesDir.toString(),
                "http://localhost:9200"), "test-commit");
    }

    private JdbcTemplate jdbc() {
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl("jdbc:h2:mem:runtime_" + System.nanoTime()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        return new JdbcTemplate(dataSource);
    }

    private static RuntimeManifest expected(JdbcTemplate jdbc) {
        return new RuntimeManifestCodec().decode(jdbc.queryForObject(
                "SELECT expected_manifest_json FROM detection_job_group", String.class));
    }

    private static RuntimeManifest withMembers(RuntimeManifest source, long generation,
                                                List<RuntimeManifest.Member> members) {
        return new RuntimeManifest(source.schemaVersion(), source.tenantId(), source.targetCluster(),
                source.jobGroupKey(), generation, "job-1", "job-key", members);
    }

    private static String planHash(RuntimeManifest expected) {
        return expected.members().getFirst().planHash();
    }

    private static String rule(String name, String value) {
        return rule("rule-runtime-test", name, value);
    }

    private static String rule(String id, String name, String value) {
        return """
                id: %s
                name: %s
                category: single_event
                type: runtime_test
                enabled: true
                severity: high
                description: runtime test
                riskScore: 50
                tags: []
                status: experimental
                version: "1.0"
                references: []
                condition:
                  type: field_equals
                  field: event.action
                  value: %s
                """.formatted(id, name, value);
    }
}
