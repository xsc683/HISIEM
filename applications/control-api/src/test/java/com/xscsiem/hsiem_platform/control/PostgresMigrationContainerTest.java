package com.xscsiem.hsiem_platform.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xscsiem.hsiem_platform.rules.runtime.DetectionRuntimeRepository;
import com.xscsiem.hsiem_platform.rules.runtime.DetectionRuntimeService;
import com.xscsiem.hsiem_platform.rules.runtime.ObservationFence;
import com.xscsiem.hsiem_platform.rules.runtime.RuntimeJobState;
import com.xscsiem.hsiem_platform.rules.runtime.RuntimeManifest;
import com.xscsiem.hsiem_platform.rules.runtime.RuntimeManifestCodec;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 用真实 PostgreSQL 容器校验 Flyway 从空库迁移到当前版本。 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresMigrationContainerTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16.4")
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
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertEquals(
                19,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM flyway_schema_history WHERE success = TRUE",
                        Integer.class));
        assertEquals(
                1,
                jdbc.queryForObject(
                        """
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name = 'lifecycle_outbox'
                """,
                        Integer.class));
        assertTrue(
                jdbc.queryForObject(
                                """
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name IN ('auth_sessions', 'login_attempts')
                """,
                                Integer.class)
                        == 2);
        assertEquals(
                3,
                jdbc.queryForObject(
                        """
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'cases'
                  AND column_name IN ('owner', 'evidence_json', 'collaborators_json')
                """,
                        Integer.class));
        assertEquals(
                4,
                jdbc.queryForObject(
                        """
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name IN ('soar_playbook', 'soar_execution', 'soar_node_run', 'soar_approval')
                """,
                        Integer.class));
        assertEquals(
                3,
                jdbc.queryForObject(
                        """
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name IN ('soar_node_execution', 'soar_approval_task', 'soar_action_receipt')
                """,
                        Integer.class));
        assertEquals(
                1,
                jdbc.queryForObject(
                        """
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'soar_execution'
                  AND column_name = 'trigger_envelope'
                """,
                        Integer.class));
        assertEquals(
                5,
                jdbc.queryForObject(
                        """
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name IN
                  ('tenants', 'tenant_memberships', 'soar_playbook_revisions',
                   'soar_connector_runtime', 'soar_connector_invocations')
                """,
                        Integer.class));
        assertEquals(
                3,
                jdbc.queryForObject(
                        """
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name IN ('soar_executions', 'soar_step_executions', 'soar_execution_events')
                """,
                        Integer.class));
        assertEquals(
                3,
                jdbc.queryForObject(
                        """
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name IN ('soar_parallel_group', 'soar_parallel_branch', 'soar_loop_state')
                """,
                        Integer.class));
        assertEquals(
                2,
                jdbc.queryForObject(
                        """
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'soar_execution'
                  AND column_name IN ('parallel_parent_id', 'trigger_type')
                """,
                        Integer.class));
        assertEquals(
                4,
                jdbc.queryForObject(
                        """
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name IN
                  ('detection_job_group', 'rule_job_assignment',
                   'detection_runtime_manifest', 'rule_runtime_status')
                """,
                        Integer.class));
        assertEquals(
                1,
                jdbc.queryForObject(
                        """
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name = 'detection_plan'
                """,
                        Integer.class));
        assertEquals(
                1,
                jdbc.queryForObject(
                        """
                SELECT COUNT(*) FROM information_schema.table_constraints tc
                JOIN information_schema.constraint_column_usage ccu
                  ON ccu.constraint_schema = tc.constraint_schema
                 AND ccu.constraint_name = tc.constraint_name
                WHERE tc.table_schema = 'public' AND tc.table_name = 'rule_job_assignment'
                  AND tc.constraint_type = 'FOREIGN KEY'
                  AND ccu.table_name = 'detection_plan' AND ccu.column_name = 'plan_id'
                """,
                        Integer.class));
        assertEquals(
                1,
                jdbc.queryForObject(
                        """
                SELECT COUNT(*) FROM information_schema.table_constraints
                WHERE table_schema = 'public' AND table_name = 'rule_job_assignment'
                  AND constraint_type = 'PRIMARY KEY'
                """,
                        Integer.class));
        assertEquals(
                2,
                jdbc.queryForObject(
                        """
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'detection_job_group'
                  AND column_name IN ('expected_manifest_json', 'expected_manifest_hash')
                """,
                        Integer.class));
        assertEquals(
                7,
                jdbc.queryForObject(
                        """
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'detection_job_group'
                  AND column_name IN ('reconcile_state', 'reconcile_available_at',
                    'controller_lease_owner', 'controller_lease_until', 'controller_fencing_token',
                    'reconcile_attempts', 'last_reconciled_at')
                """,
                        Integer.class));
        assertEquals(
                1,
                jdbc.queryForObject(
                        """
                SELECT COUNT(*) FROM information_schema.table_constraints
                WHERE table_schema = 'public' AND table_name = 'detection_job_group'
                  AND constraint_name = 'detection_job_group_reconcile_state_ck'
                """,
                        Integer.class));
        assertEquals(
                1,
                jdbc.queryForObject(
                        """
                SELECT COUNT(*) FROM pg_indexes
                WHERE schemaname = 'public' AND indexname = 'detection_job_group_reconcile_due_idx'
                """,
                        Integer.class));
        assertEquals(
                1,
                jdbc.queryForObject(
                        """
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'rule_job_assignment'
                  AND column_name = 'plan_id'
                """,
                        Integer.class));
        assertEquals(
                1,
                jdbc.queryForObject(
                        """
                SELECT COUNT(*) FROM information_schema.table_constraints
                WHERE table_schema = 'public' AND table_name = 'rule_job_assignment'
                  AND constraint_name = 'rule_job_assignment_group_fk'
                """,
                        Integer.class));
    }

    @Test
    void concurrentLifecycleClaimsSkipTheRowLockedByFirstTransaction() throws Exception {
        DriverManagerDataSource dataSource = dataSource();
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        MyBatisControlPlaneStore store = controlPlaneStore(dataSource);
        String messageId = "lifecycle-concurrent-" + System.nanoTime();
        store.enqueueLifecycle(
                messageId,
                "alert.created",
                "tenant-a",
                "alert",
                "alert-a",
                Instant.now(),
                "alerts",
                "alert-a",
                "{}");

        TransactionTemplate transaction =
                new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        CountDownLatch firstClaimed = new CountDownLatch(1);
        CountDownLatch secondFinished = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first =
                    executor.submit(
                            () ->
                                    transaction.executeWithoutResult(
                                            status -> {
                                                List<Map<String, Object>> rows =
                                                        store.claimLifecycleBatch(
                                                                "owner-a",
                                                                Instant.now().plusSeconds(60),
                                                                1);
                                                assertEquals(1, rows.size());
                                                firstClaimed.countDown();
                                                try {
                                                    assertTrue(
                                                            secondFinished.await(
                                                                    10, TimeUnit.SECONDS));
                                                } catch (InterruptedException e) {
                                                    Thread.currentThread().interrupt();
                                                    throw new IllegalStateException(e);
                                                }
                                            }));
            Future<List<Map<String, Object>>> second =
                    executor.submit(
                            () -> {
                                assertTrue(firstClaimed.await(10, TimeUnit.SECONDS));
                                try {
                                    return transaction.execute(
                                            status ->
                                                    store.claimLifecycleBatch(
                                                            "owner-b",
                                                            Instant.now().plusSeconds(60),
                                                            1));
                                } finally {
                                    secondFinished.countDown();
                                }
                            });

            assertTrue(second.get(15, TimeUnit.SECONDS).isEmpty());
            first.get(15, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void postgresFencedObservationRejectsStaleEpochWithoutObservedMutation() {
        DriverManagerDataSource dataSource = dataSource();
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        String suffix = Long.toString(System.nanoTime());
        String ruleKey = "fence-pg-" + suffix;
        String groupKey = "fence-pg-group-" + suffix;
        UUID revisionId = UUID.randomUUID();
        UUID deploymentId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO detection_rule (rule_key, name, description, category) VALUES (?, ?, ?, ?)",
                ruleKey,
                ruleKey,
                "",
                "single_event");
        jdbc.update(
                """
                INSERT INTO rule_revision
                    (revision_id, rule_key, revision, definition_json, content_hash, source_commit, created_by)
                VALUES (?, ?, 1, '{}', ?, 'test', 'test')
                """,
                revisionId,
                ruleKey,
                "fence-content-" + suffix);
        jdbc.update(
                """
                INSERT INTO rule_deployment
                    (deployment_id, tenant_id, rule_key, desired_revision_id, desired_state,
                     generation, observed_generation, target_cluster, status)
                VALUES (?, 'default', ?, ?, 'RUNNING', 1, 0, 'cluster-a', 'PENDING')
                """,
                deploymentId,
                ruleKey,
                revisionId);
        RuntimeManifest expected =
                new RuntimeManifest(
                        RuntimeManifest.SCHEMA_VERSION,
                        "default",
                        "cluster-a",
                        groupKey,
                        1L,
                        List.of());
        RuntimeManifestCodec codec = new RuntimeManifestCodec();
        jdbc.update(
                """
                INSERT INTO detection_job_group
                    (tenant_id, group_key, target_cluster, source_family, category, bucket,
                     desired_generation, expected_manifest_json, expected_manifest_hash, status,
                     controller_lease_owner, controller_lease_until, controller_fencing_token)
                VALUES ('default', ?, 'cluster-a', 'siem-events', 'single_event', 0,
                        1, ?, ?, 'PENDING', 'controller-a', CURRENT_TIMESTAMP + INTERVAL '1 hour', 7)
                """,
                groupKey,
                codec.encode(expected),
                codec.specHash(expected));
        jdbc.update(
                """
                INSERT INTO rule_runtime_status
                    (tenant_id, rule_key, deployment_id, group_key, target_cluster, runtime_state)
                VALUES ('default', ?, ?, ?, 'cluster-a', 'UNKNOWN')
                """,
                ruleKey,
                deploymentId,
                groupKey);
        RuntimeManifest observed =
                new RuntimeManifest(
                        RuntimeManifest.SCHEMA_VERSION,
                        "default",
                        "cluster-a",
                        groupKey,
                        1L,
                        "job-1",
                        "key-1",
                        List.of());
        DetectionRuntimeService runtime =
                new DetectionRuntimeService(new DetectionRuntimeRepository(jdbc), 1);
        TransactionTemplate transaction =
                new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        List<ObservationFence> staleFences =
                List.of(
                        new ObservationFence(
                                "default", groupKey, "cluster-a", "controller-b", 7, 1),
                        new ObservationFence(
                                "default", groupKey, "cluster-a", "controller-a", 8, 1),
                        new ObservationFence(
                                "default", groupKey, "cluster-a", "controller-a", 7, 2));
        for (ObservationFence stale : staleFences) {
            jdbc.update(
                    "UPDATE detection_job_group SET controller_lease_until = CURRENT_TIMESTAMP + INTERVAL '1 hour' WHERE group_key = ?",
                    groupKey);
            assertThrows(
                    DetectionRuntimeService.StaleObservationException.class,
                    () ->
                            transaction.executeWithoutResult(
                                    status ->
                                            runtime.observeFenced(
                                                    observed,
                                                    RuntimeJobState.RUNNING,
                                                    null,
                                                    null,
                                                    stale)));
            assertUnchangedObservedScope(jdbc, ruleKey, groupKey);
        }
        jdbc.update(
                "UPDATE detection_job_group SET controller_lease_until = CURRENT_TIMESTAMP - INTERVAL '1 hour' WHERE group_key = ?",
                groupKey);
        ObservationFence expired =
                new ObservationFence("default", groupKey, "cluster-a", "controller-a", 7, 1);
        assertThrows(
                DetectionRuntimeService.StaleObservationException.class,
                () ->
                        transaction.executeWithoutResult(
                                status ->
                                        runtime.observeFenced(
                                                observed,
                                                RuntimeJobState.RUNNING,
                                                null,
                                                null,
                                                expired)));
        assertUnchangedObservedScope(jdbc, ruleKey, groupKey);
    }

    private static void assertUnchangedObservedScope(
            JdbcTemplate jdbc, String ruleKey, String groupKey) {
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM detection_runtime_manifest WHERE group_key = ?",
                        Integer.class,
                        groupKey));
        Map<String, Object> group =
                jdbc.queryForMap(
                        "SELECT status, job_id, job_key, last_error FROM detection_job_group WHERE group_key = ?",
                        groupKey);
        assertEquals("PENDING", group.get("status"));
        assertTrue(
                group.get("job_id") == null
                        && group.get("job_key") == null
                        && group.get("last_error") == null);
        Map<String, Object> runtime =
                jdbc.queryForMap(
                        """
                SELECT runtime_state, job_id, job_key, observed_revision, observed_generation,
                       observed_plan_hash, error_code, error_message
                FROM rule_runtime_status WHERE rule_key = ?
                """,
                        ruleKey);
        assertEquals("UNKNOWN", runtime.get("runtime_state"));
        assertTrue(
                runtime.get("job_id") == null
                        && runtime.get("job_key") == null
                        && runtime.get("observed_revision") == null
                        && runtime.get("observed_generation") == null
                        && runtime.get("observed_plan_hash") == null
                        && runtime.get("error_code") == null
                        && runtime.get("error_message") == null);
        Map<String, Object> deployment =
                jdbc.queryForMap(
                        """
                SELECT status, observed_generation, last_error
                FROM rule_deployment WHERE rule_key = ?
                """,
                        ruleKey);
        assertEquals("PENDING", deployment.get("status"));
        assertEquals(0L, ((Number) deployment.get("observed_generation")).longValue());
        assertTrue(deployment.get("last_error") == null);
    }

    private static DriverManagerDataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUsername(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        return dataSource;
    }

    /**
     * Builds the MyBatis control-plane store over the container datasource without a Spring
     * context.
     */
    private static MyBatisControlPlaneStore controlPlaneStore(DriverManagerDataSource dataSource) {
        try {
            SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setMapperLocations(
                    new PathMatchingResourcePatternResolver()
                            .getResources("classpath*:mybatis/control/*.xml"));
            SqlSessionFactory sessionFactory = factory.getObject();
            if (sessionFactory == null) {
                throw new IllegalStateException(
                        "control-plane MyBatis session factory was not created");
            }
            SqlSessionTemplate template = new SqlSessionTemplate(sessionFactory);
            return new MyBatisControlPlaneStore(
                    dataSource,
                    template.getMapper(UserAuthMapper.class),
                    template.getMapper(RoleAuditMapper.class),
                    template.getMapper(NotificationMapper.class),
                    template.getMapper(CaseMapper.class),
                    template.getMapper(CaseMirrorOutboxMapper.class),
                    template.getMapper(TaskMapper.class),
                    template.getMapper(LifecycleOutboxMapper.class));
        } catch (Exception e) {
            throw new IllegalStateException("cannot initialize control-plane MyBatis store", e);
        }
    }
}
