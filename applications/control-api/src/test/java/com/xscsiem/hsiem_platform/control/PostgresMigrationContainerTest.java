package com.xscsiem.hsiem_platform.control;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 用真实 PostgreSQL 容器校验 Flyway 从空库迁移到当前版本。 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresMigrationContainerTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4")
            .withDatabaseName("siem_test")
            .withUsername("siem")
            .withPassword("siem_test");

    @Test
    void flywayCreatesCurrentSchemaInPostgres() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUsername(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertEquals(18, jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = TRUE", Integer.class));
        assertTrue(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name IN ('auth_sessions', 'login_attempts')
                """, Integer.class) == 2);
        assertEquals(3, jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'cases'
                  AND column_name IN ('owner', 'evidence_json', 'collaborators_json')
                """, Integer.class));
        assertEquals(4, jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name IN ('soar_playbook', 'soar_execution', 'soar_node_run', 'soar_approval')
                """, Integer.class));
        assertEquals(3, jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name IN ('soar_node_execution', 'soar_approval_task', 'soar_action_receipt')
                """, Integer.class));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'soar_execution'
                  AND column_name = 'trigger_envelope'
                """, Integer.class));
        assertEquals(5, jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name IN
                  ('tenants', 'tenant_memberships', 'soar_playbook_revisions',
                   'soar_connector_runtime', 'soar_connector_invocations')
                """, Integer.class));
        assertEquals(3, jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name IN ('soar_executions', 'soar_step_executions', 'soar_execution_events')
                """, Integer.class));
        assertEquals(3, jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name IN ('soar_parallel_group', 'soar_parallel_branch', 'soar_loop_state')
                """, Integer.class));
        assertEquals(2, jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'soar_execution'
                  AND column_name IN ('parallel_parent_id', 'trigger_type')
                """, Integer.class));
        assertEquals(4, jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name IN
                  ('detection_job_group', 'rule_job_assignment',
                   'detection_runtime_manifest', 'rule_runtime_status')
                """, Integer.class));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name = 'detection_plan'
                """, Integer.class));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.table_constraints tc
                JOIN information_schema.constraint_column_usage ccu
                  ON ccu.constraint_schema = tc.constraint_schema
                 AND ccu.constraint_name = tc.constraint_name
                WHERE tc.table_schema = 'public' AND tc.table_name = 'rule_job_assignment'
                  AND tc.constraint_type = 'FOREIGN KEY'
                  AND ccu.table_name = 'detection_plan' AND ccu.column_name = 'plan_id'
                """, Integer.class));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.table_constraints
                WHERE table_schema = 'public' AND table_name = 'rule_job_assignment'
                  AND constraint_type = 'PRIMARY KEY'
                """, Integer.class));
        assertEquals(2, jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'detection_job_group'
                  AND column_name IN ('expected_manifest_json', 'expected_manifest_hash')
                """, Integer.class));
        assertEquals(7, jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'detection_job_group'
                  AND column_name IN ('reconcile_state', 'reconcile_available_at',
                    'controller_lease_owner', 'controller_lease_until', 'controller_fencing_token',
                    'reconcile_attempts', 'last_reconciled_at')
                """, Integer.class));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.table_constraints
                WHERE table_schema = 'public' AND table_name = 'detection_job_group'
                  AND constraint_name = 'detection_job_group_reconcile_state_ck'
                """, Integer.class));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM pg_indexes
                WHERE schemaname = 'public' AND indexname = 'detection_job_group_reconcile_due_idx'
                """, Integer.class));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'rule_job_assignment'
                  AND column_name = 'plan_id'
                """, Integer.class));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.table_constraints
                WHERE table_schema = 'public' AND table_name = 'rule_job_assignment'
                  AND constraint_name = 'rule_job_assignment_group_fk'
                """, Integer.class));
    }
}
