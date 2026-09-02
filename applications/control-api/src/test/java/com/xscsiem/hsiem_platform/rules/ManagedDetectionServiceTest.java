package com.xscsiem.hsiem_platform.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xscsiem.hsiem_platform.rules.runtime.DetectionRuntimeRepository;
import com.xscsiem.hsiem_platform.rules.runtime.DetectionRuntimeService;
import com.xscsiem.hsiem_platform.rules.runtime.RuntimeManifest;
import com.xscsiem.hsiem_platform.rules.runtime.RuntimeManifestCodec;
import com.xscsiem.hsiem_platform.tenant.TenantContext;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

class ManagedDetectionServiceTest {

    @TempDir Path rulesDir;

    private HikariDataSource dataSource;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void inspectionIsPureQueryAndDoesNotCreateManagedState() throws Exception {
        Files.writeString(
                rulesDir.resolve("rule-managed-test.yaml"), rule("managed test", "login"));
        JdbcTemplate jdbc = jdbc();
        ManagedDetectionService managed =
                new ManagedDetectionService(
                        new ManagedDetectionRepository(jdbc),
                        new RuleService(rulesDir.toString(), "http://localhost:9200"),
                        "test-commit",
                        new DetectionRuntimeService(new DetectionRuntimeRepository(jdbc), 1));
        TenantContext.set("default");

        Map<String, Object> inspected = managed.inspect("rule-managed-test", "tester");

        assertNotNull(inspected.get("rule"));
        assertNull(inspected.get("revision"));
        assertNull(inspected.get("plan"));
        assertNull(inspected.get("deployment"));
        assertNull(inspected.get("assignment"));
        assertNull(inspected.get("jobGroup"));
        assertNull(inspected.get("runtimeStatus"));
        assertNoManagedRows(jdbc);
    }

    @Test
    void explicitDeployEstablishesArtifactsAndIdenticalDeployIsIdempotent() throws Exception {
        Files.writeString(
                rulesDir.resolve("rule-managed-test.yaml"), rule("managed test", "login"));
        JdbcTemplate jdbc = jdbc();
        ManagedDetectionService managed =
                new ManagedDetectionService(
                        new ManagedDetectionRepository(jdbc),
                        new RuleService(rulesDir.toString(), "http://localhost:9200"),
                        "test-commit",
                        new DetectionRuntimeService(new DetectionRuntimeRepository(jdbc), 1));
        TenantContext.set("default");

        Map<String, Object> first =
                managed.deploy("default", "rule-managed-test", Map.of(), "tester");
        assertEquals("RUNNING", first.get("desired_state"));
        assertNotNull(first.get("desired_revision_id"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM rule_revision", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM detection_plan", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM rule_deployment", Integer.class));
        assertEquals(
                1,
                jdbc.queryForObject("SELECT COUNT(*) FROM rule_deployment_history", Integer.class));

        Map<String, Object> inspected = managed.inspect("rule-managed-test", "tester");
        assertNotNull(inspected.get("revision"));
        assertNotNull(inspected.get("plan"));
        assertNotNull(inspected.get("deployment"));
        assertNotNull(inspected.get("assignment"));
        assertNotNull(inspected.get("jobGroup"));
        assertNotNull(inspected.get("runtimeStatus"));
        long generation = ((Number) first.get("generation")).longValue();

        Map<String, Object> repeated =
                managed.deploy("default", "rule-managed-test", Map.of(), "tester");

        assertEquals(generation, ((Number) repeated.get("generation")).longValue());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM rule_revision", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM detection_plan", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM rule_deployment", Integer.class));
        assertEquals(
                1,
                jdbc.queryForObject("SELECT COUNT(*) FROM rule_deployment_history", Integer.class));
    }

    @Test
    void invalidRevisionDefinitionFailsWithoutCreatingPlanOrDeployment() throws Exception {
        Files.writeString(
                rulesDir.resolve("rule-managed-test.yaml"), rule("managed test", "login"));
        JdbcTemplate jdbc = jdbc();
        ManagedDetectionService managed =
                new ManagedDetectionService(
                        new ManagedDetectionRepository(jdbc),
                        new RuleService(rulesDir.toString(), "http://localhost:9200"),
                        "test-commit",
                        new DetectionRuntimeService(new DetectionRuntimeRepository(jdbc), 1));
        TenantContext.set("default");
        jdbc.update(
                "INSERT INTO detection_rule (rule_key, name, description, category) VALUES (?, ?, ?, ?)",
                "rule-managed-test",
                "managed test",
                "",
                "single_event");
        UUID revisionId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO rule_revision
                    (revision_id, rule_key, revision, definition_json, content_hash, source_commit, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                revisionId,
                "rule-managed-test",
                99,
                "{not-json",
                "invalid-revision-hash",
                "test-commit",
                "tester");

        IllegalStateException failure =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                managed.deploy(
                                        "default",
                                        "rule-managed-test",
                                        Map.of("revisionId", revisionId),
                                        "tester"));
        assertTrue(failure.getMessage().contains(revisionId.toString()));
        assertNotNull(failure.getCause());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM detection_plan", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM rule_deployment", Integer.class));
        assertEquals(
                0,
                jdbc.queryForObject("SELECT COUNT(*) FROM rule_deployment_history", Integer.class));
    }

    @Test
    void repeatedStopAndRollbackToTheSameRevisionAreIdempotent() throws Exception {
        Files.writeString(
                rulesDir.resolve("rule-managed-test.yaml"), rule("managed test", "login"));
        JdbcTemplate jdbc = jdbc();
        ManagedDetectionService managed =
                new ManagedDetectionService(
                        new ManagedDetectionRepository(jdbc),
                        new RuleService(rulesDir.toString(), "http://localhost:9200"),
                        "test-commit",
                        new DetectionRuntimeService(new DetectionRuntimeRepository(jdbc), 1));
        TenantContext.set("default");

        Map<String, Object> deployed =
                managed.deploy("default", "rule-managed-test", Map.of(), "tester");
        Object revisionId = deployed.get("desired_revision_id");

        Map<String, Object> stopped = managed.stop("default", "rule-managed-test", "tester");
        assertEquals("STOPPED", stopped.get("desired_state"));
        assertEquals(2L, ((Number) stopped.get("generation")).longValue());
        assertEquals(
                2,
                jdbc.queryForObject("SELECT COUNT(*) FROM rule_deployment_history", Integer.class));
        long stoppedGroupGeneration =
                jdbc.queryForObject(
                        "SELECT desired_generation FROM detection_job_group", Long.class);

        Map<String, Object> repeatedStop = managed.stop("default", "rule-managed-test", "tester");
        assertEquals(2L, ((Number) repeatedStop.get("generation")).longValue());
        assertEquals(
                2,
                jdbc.queryForObject("SELECT COUNT(*) FROM rule_deployment_history", Integer.class));
        assertEquals(
                stoppedGroupGeneration,
                jdbc.queryForObject(
                        "SELECT desired_generation FROM detection_job_group", Long.class));

        Map<String, Object> rolledBack =
                managed.rollback(
                        "default", "rule-managed-test", Map.of("revisionId", revisionId), "tester");
        assertEquals("RUNNING", rolledBack.get("desired_state"));
        assertEquals(3L, ((Number) rolledBack.get("generation")).longValue());
        assertEquals(
                3,
                jdbc.queryForObject("SELECT COUNT(*) FROM rule_deployment_history", Integer.class));
        long rolledBackGroupGeneration =
                jdbc.queryForObject(
                        "SELECT desired_generation FROM detection_job_group", Long.class);

        Map<String, Object> repeatedRollback =
                managed.rollback(
                        "default", "rule-managed-test", Map.of("revisionId", revisionId), "tester");
        assertEquals(3L, ((Number) repeatedRollback.get("generation")).longValue());
        assertEquals(
                3,
                jdbc.queryForObject("SELECT COUNT(*) FROM rule_deployment_history", Integer.class));
        assertEquals(
                rolledBackGroupGeneration,
                jdbc.queryForObject(
                        "SELECT desired_generation FROM detection_job_group", Long.class));
    }

    @Test
    void authoringOnlyChangesCreateRevisionButPreserveCanonicalPhysicalDesiredState()
            throws Exception {
        Path ruleFile = rulesDir.resolve("rule-managed-test.yaml");
        Files.writeString(ruleFile, rule("managed test", "login", true));
        JdbcTemplate jdbc = jdbc();
        ManagedDetectionService managed =
                new ManagedDetectionService(
                        new ManagedDetectionRepository(jdbc),
                        new RuleService(rulesDir.toString(), "http://localhost:9200"),
                        "test-commit",
                        new DetectionRuntimeService(new DetectionRuntimeRepository(jdbc), 1));
        TenantContext.set("default");

        managed.deploy("default", "rule-managed-test", Map.of(), "tester");
        Map<String, Object> firstRevision = managed.inspect("rule-managed-test", "tester");
        @SuppressWarnings("unchecked")
        Map<String, Object> firstPlan = (Map<String, Object>) firstRevision.get("plan");
        String firstContentHash =
                String.valueOf(
                        ((Map<String, Object>) firstRevision.get("revision")).get("contentHash"));
        String firstPlanHash = String.valueOf(firstPlan.get("planHash"));
        Map<String, Object> physicalBefore =
                jdbc.queryForMap(
                        """
                SELECT plan_hash, group_key, generation
                FROM rule_job_assignment WHERE tenant_id = 'default' AND rule_key = 'rule-managed-test'
                """);
        Map<String, Object> groupBefore =
                jdbc.queryForMap(
                        """
                SELECT desired_generation, expected_manifest_json, expected_manifest_hash
                FROM detection_job_group WHERE tenant_id = 'default'
                """);
        long deploymentGeneration =
                jdbc.queryForObject(
                        "SELECT generation FROM rule_deployment WHERE tenant_id = 'default' AND rule_key = 'rule-managed-test'",
                        Long.class);

        Files.writeString(
                ruleFile, rule("managed test", "login", false, "https://example.test/reference"));
        managed.deploy("default", "rule-managed-test", Map.of(), "tester");

        Map<String, Object> secondRevision = managed.inspect("rule-managed-test", "tester");
        @SuppressWarnings("unchecked")
        Map<String, Object> secondRevisionView =
                (Map<String, Object>) secondRevision.get("revision");
        @SuppressWarnings("unchecked")
        Map<String, Object> secondPlan = (Map<String, Object>) secondRevision.get("plan");
        assertNotEquals(firstContentHash, secondRevisionView.get("contentHash"));
        assertNotEquals(
                ((Map<String, Object>) firstRevision.get("revision")).get("revisionId"),
                secondRevisionView.get("revisionId"));
        assertEquals(firstPlanHash, secondPlan.get("planHash"));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM rule_revision", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM detection_plan", Integer.class));
        assertEquals(
                deploymentGeneration + 1,
                jdbc.queryForObject(
                        "SELECT generation FROM rule_deployment WHERE tenant_id = 'default' AND rule_key = 'rule-managed-test'",
                        Long.class));
        assertEquals(
                2,
                jdbc.queryForObject("SELECT COUNT(*) FROM rule_deployment_history", Integer.class));

        Map<String, Object> physicalAfter =
                jdbc.queryForMap(
                        """
                SELECT plan_hash, group_key, generation
                FROM rule_job_assignment WHERE tenant_id = 'default' AND rule_key = 'rule-managed-test'
                """);
        Map<String, Object> groupAfter =
                jdbc.queryForMap(
                        """
                SELECT desired_generation, expected_manifest_json, expected_manifest_hash
                FROM detection_job_group WHERE tenant_id = 'default'
                """);
        assertEquals(physicalBefore.get("plan_hash"), physicalAfter.get("plan_hash"));
        assertEquals(physicalBefore.get("group_key"), physicalAfter.get("group_key"));
        assertEquals(physicalBefore.get("generation"), physicalAfter.get("generation"));
        assertEquals(groupBefore.get("desired_generation"), groupAfter.get("desired_generation"));
        assertEquals(
                groupBefore.get("expected_manifest_hash"),
                groupAfter.get("expected_manifest_hash"));
        RuntimeManifest.Member physicalMember =
                physicalMember(groupAfter.get("expected_manifest_json"));
        assertEquals(firstPlanHash, physicalMember.planHash());
    }

    private static RuntimeManifest.Member physicalMember(Object manifestJson) {
        return new RuntimeManifestCodec()
                .decode(String.valueOf(manifestJson))
                .member("rule-managed-test");
    }

    private static void assertNoManagedRows(JdbcTemplate jdbc) {
        for (String table :
                List.of(
                        "detection_rule",
                        "rule_revision",
                        "detection_plan",
                        "rule_deployment",
                        "rule_deployment_history",
                        "rule_job_assignment",
                        "detection_job_group",
                        "rule_runtime_status")) {
            assertEquals(
                    0, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class), table);
        }
    }

    private JdbcTemplate jdbc() {
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(
                "jdbc:h2:mem:managed_detection_"
                        + System.nanoTime()
                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        return new JdbcTemplate(dataSource);
    }

    private static String rule(String name, String value) {
        return rule(name, value, true);
    }

    private static String rule(String name, String value, boolean enabled, String... references) {
        String referenceYaml =
                references.length == 0 ? "[]" : "[\"" + String.join("\", \"", references) + "\"]";
        return """
                id: rule-managed-test
                name: %s
                category: single_event
                type: managed_test
                enabled: %s
                severity: high
                description: managed runtime test
                riskScore: 50
                tags: []
                status: experimental
                version: "1.0"
                references: %s
                condition:
                  type: field_equals
                  field: event.action
                  value: %s
                """
                .formatted(name, enabled, referenceYaml, value);
    }
}
