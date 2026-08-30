package com.xscsiem.hsiem_platform.rules;

import com.xscsiem.hsiem_platform.tenant.TenantContext;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import com.zaxxer.hikari.HikariDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ManagedDetectionServiceTest {

    @TempDir
    Path rulesDir;

    private HikariDataSource dataSource;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void reusesExistingPlanArtifactBeforeCompiling() throws Exception {
        Files.writeString(rulesDir.resolve("rule-managed-test.yaml"), rule("managed test", "login"));
        JdbcTemplate jdbc = jdbc();
        ManagedDetectionService managed = new ManagedDetectionService(
                jdbc, new RuleService(rulesDir.toString(), "http://localhost:9200"), "test-commit");
        TenantContext.set("default");

        Map<String, Object> first = managed.inspect("rule-managed-test", "tester");
        @SuppressWarnings("unchecked")
        Map<String, Object> firstPlan = (Map<String, Object>) first.get("plan");
        Object planId = firstPlan.get("planId");
        jdbc.update("UPDATE detection_plan SET plan_json = ?, plan_hash = ? WHERE plan_id = ?",
                "{\"persisted\":true}", "persisted-artifact-hash", planId);

        Map<String, Object> second = managed.inspect("rule-managed-test", "tester");
        @SuppressWarnings("unchecked")
        Map<String, Object> secondPlan = (Map<String, Object>) second.get("plan");
        assertEquals(planId, secondPlan.get("planId"));
        assertEquals("persisted-artifact-hash", secondPlan.get("planHash"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM detection_plan", Integer.class));
    }

    @Test
    void persistsRevisionPlanDesiredGenerationAndRollback() throws Exception {
        Files.writeString(rulesDir.resolve("rule-managed-test.yaml"), rule("managed test", "login"));
        JdbcTemplate jdbc = jdbc();
        ManagedDetectionService managed = new ManagedDetectionService(
                jdbc, new RuleService(rulesDir.toString(), "http://localhost:9200"), "test-commit");
        TenantContext.set("default");

        Map<String, Object> first = managed.inspect("rule-managed-test", "tester");
        Map<String, Object> second = managed.inspect("rule-managed-test", "tester");
        assertEquals(first.get("revision"), second.get("revision"));
        assertNotNull(first.get("plan"));

        @SuppressWarnings("unchecked")
        Map<String, Object> revision = (Map<String, Object>) first.get("revision");
        Map<String, Object> deployed = managed.deploy("default", "rule-managed-test",
                Map.of("revisionId", revision.get("revisionId")), "tester");
        assertEquals("RUNNING", deployed.get("desired_state"));
        assertEquals(1L, ((Number) deployed.get("generation")).longValue());

        Map<String, Object> stopped = managed.stop("default", "rule-managed-test", "tester");
        assertEquals("STOPPED", stopped.get("desired_state"));
        assertEquals(2L, ((Number) stopped.get("generation")).longValue());

        Map<String, Object> rolledBack = managed.rollback("default", "rule-managed-test",
                Map.of("revisionId", revision.get("revisionId")), "tester");
        assertEquals("RUNNING", rolledBack.get("desired_state"));
        assertEquals(3L, ((Number) rolledBack.get("generation")).longValue());
        assertEquals(3, jdbc.queryForObject(
                "SELECT COUNT(*) FROM rule_deployment_history", Integer.class));
    }

    private JdbcTemplate jdbc() {
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl("jdbc:h2:mem:managed_detection_" + System.nanoTime()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        return new JdbcTemplate(dataSource);
    }

    private static String rule(String name, String value) {
        return """
                id: rule-managed-test
                name: %s
                category: single_event
                type: managed_test
                enabled: true
                severity: high
                description: managed runtime test
                riskScore: 50
                tags: []
                status: experimental
                version: "1.0"
                references: []
                condition:
                  type: field_equals
                  field: event.action
                  value: %s
                """.formatted(name, value);
    }
}
