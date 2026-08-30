package com.xscsiem.hsiem_platform.detection.controller;

import com.xscsiem.hsiem_platform.detection.runtime.DetectionGroupLease;
import com.xscsiem.hsiem_platform.rules.runtime.DetectionRuntimeRepository;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DetectionControllerRepositoryTest {

    private HikariDataSource dataSource;
    private JdbcTemplate jdbc;
    private DetectionControllerRepository repository;
    private TransactionTemplate transactions;

    @BeforeEach
    void setUp() {
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl("jdbc:h2:mem:controller_" + System.nanoTime()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        repository = new DetectionControllerRepository(jdbc);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) dataSource.close();
    }

    @Test
    void claimIsFencedAndOnlyOneOfTwoConcurrentWorkersWins() throws Exception {
        insertGroup("g-concurrent", "RUNNING");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<List<DetectionGroupLease>> first = executor.submit(() ->
                    transactions.execute(status -> repository.claimDue("worker-a", Duration.ofSeconds(30), 10)));
            Future<List<DetectionGroupLease>> second = executor.submit(() ->
                    transactions.execute(status -> repository.claimDue("worker-b", Duration.ofSeconds(30), 10)));
            List<DetectionGroupLease> a = first.get();
            List<DetectionGroupLease> b = second.get();
            assertEquals(1, (a.size() + b.size()));
            DetectionGroupLease lease = a.isEmpty() ? b.getFirst() : a.getFirst();
            assertEquals(1L, lease.fencingToken());
            assertEquals(1, lease.attempt());
            assertEquals("INSPECTING", state());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void expiredLeaseIsTakenOverWithMonotonicTokenAndAttempt() {
        insertGroup("g-expire", "PENDING");
        DetectionGroupLease old = transactions.execute(status ->
                repository.claimDue("worker-a", Duration.ofSeconds(30), 1).getFirst());
        jdbc.update("UPDATE detection_job_group SET controller_lease_until = ? WHERE group_key = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), "g-expire");
        DetectionGroupLease next = transactions.execute(status ->
                repository.claimDue("worker-b", Duration.ofSeconds(30), 1).getFirst());
        assertEquals(old.fencingToken() + 1, next.fencingToken());
        assertEquals(old.attempt() + 1, next.attempt());
        assertEquals("worker-b", next.owner());
    }

    @Test
    void staleTokenAndGenerationCannotHeartbeatPhaseReleaseOrFail() {
        insertGroup("g-stale", "PENDING");
        DetectionGroupLease lease = transactions.execute(status ->
                repository.claimDue("worker-a", Duration.ofSeconds(30), 1).getFirst());
        DetectionGroupLease wrongToken = new DetectionGroupLease(lease.tenantId(), lease.groupKey(),
                lease.targetCluster(), lease.desiredGeneration(), lease.expectedJson(), lease.expectedHash(),
                lease.owner(), lease.fencingToken() + 1, lease.attempt());
        DetectionGroupLease wrongGeneration = new DetectionGroupLease(lease.tenantId(), lease.groupKey(),
                lease.targetCluster(), lease.desiredGeneration() + 1, lease.expectedJson(), lease.expectedHash(),
                lease.owner(), lease.fencingToken(), lease.attempt());
        assertFalse(repository.heartbeat(wrongToken, Duration.ofSeconds(30)));
        assertFalse(repository.transitionPhase(wrongToken, ReconcileState.APPLYING));
        assertFalse(repository.release(wrongToken));
        assertFalse(repository.fail(wrongToken, "stale"));
        assertFalse(repository.heartbeat(wrongGeneration, Duration.ofSeconds(30)));
        assertTrue(repository.isCurrent(lease));
    }

    @Test
    void desiredGenerationUpdateMakesFailedFutureGroupDueAndFencesOldLease() {
        insertGroup("g-generation", "PENDING");
        DetectionGroupLease old = transactions.execute(status ->
                repository.claimDue("worker-a", Duration.ofSeconds(30), 1).getFirst());
        Instant future = Instant.now().plusSeconds(3600);
        jdbc.update("""
                UPDATE detection_job_group
                SET reconcile_state = 'FAILED', reconcile_available_at = ?,
                    controller_lease_until = ?
                WHERE tenant_id = ? AND group_key = ?
                """, Timestamp.from(future), Timestamp.from(future), old.tenantId(), old.groupKey());

        new DetectionRuntimeRepository(jdbc).upsertGroup(
                old.tenantId(), old.groupKey(), old.targetCluster(), "siem-events", "single_event", 0,
                old.desiredGeneration() + 1, "new-manifest", "new-hash");

        assertEquals("PENDING", state());
        Instant availableAt = jdbc.queryForObject("SELECT reconcile_available_at FROM detection_job_group",
                OffsetDateTime.class).toInstant();
        assertTrue(!availableAt.isAfter(Instant.now().plusSeconds(2)),
                "desired changes must be due immediately");
        assertEquals(old.owner(), jdbc.queryForObject(
                "SELECT controller_lease_owner FROM detection_job_group", String.class));
        assertFalse(repository.isCurrent(old),
                "the old generation must be fenced without clearing its lease");

        DetectionGroupLease next = transactions.execute(status ->
                repository.claimDue("worker-b", Duration.ofSeconds(30), 1).getFirst());
        assertEquals(old.fencingToken() + 1, next.fencingToken());
        assertEquals("worker-b", next.owner());
        assertEquals(old.desiredGeneration() + 1, next.desiredGeneration());
    }

    @Test
    void successfulReleaseResetsAttemptsBeforeNextFailure() {
        insertGroup("g-reset", "PENDING");
        DetectionGroupLease lease = claim("worker-a");
        for (int i = 0; i < 3; i++) {
            assertTrue(repository.releaseAt(lease, Instant.now().minusSeconds(1)));
            assertEquals(0, jdbc.queryForObject(
                    "SELECT reconcile_attempts FROM detection_job_group", Integer.class));
            if (i < 2) {
                lease = claim("worker-a");
            }
        }

        lease = claim("worker-a");
        Instant beforeFailure = Instant.now();
        assertTrue(repository.fail(lease, "first failure after successful reconciles"));
        Instant availableAt = jdbc.queryForObject("SELECT reconcile_available_at FROM detection_job_group",
                OffsetDateTime.class).toInstant();
        assertTrue(!availableAt.isBefore(beforeFailure.plusMillis(500)));
        assertTrue(!availableAt.isAfter(beforeFailure.plusSeconds(5)),
                "first failure after a successful release must use base backoff");
    }

    private DetectionGroupLease claim(String owner) {
        return transactions.execute(status ->
                repository.claimDue(owner, Duration.ofSeconds(30), 1).getFirst());
    }

    @Test
    void failureUsesBoundedExponentialBackoffAndDoesNotTouchRuntimeColumns() {
        insertGroup("g-fail", "PENDING");
        DetectionGroupLease lease = transactions.execute(status ->
                repository.claimDue("worker-a", Duration.ofSeconds(30), 1).getFirst());
        jdbc.update("UPDATE detection_job_group SET status = 'RUNNING', job_id = 'job-1', job_key = 'key-1'");
        assertTrue(repository.fail(lease, "port unavailable"));
        assertEquals("FAILED", state());
        assertEquals("job-1", jdbc.queryForObject("SELECT job_id FROM detection_job_group", String.class));
        assertEquals("key-1", jdbc.queryForObject("SELECT job_key FROM detection_job_group", String.class));
        assertEquals("port unavailable", jdbc.queryForObject("SELECT last_error FROM detection_job_group", String.class));
        assertTrue(jdbc.queryForObject("SELECT reconcile_available_at FROM detection_job_group", java.time.OffsetDateTime.class)
                .isAfter(java.time.OffsetDateTime.now().minusSeconds(1)));
    }

    private void insertGroup(String groupKey, String status) {
        jdbc.update("""
                INSERT INTO detection_job_group
                    (tenant_id, group_key, target_cluster, source_family, category, bucket,
                     desired_generation, expected_manifest_json, expected_manifest_hash, status,
                     reconcile_available_at)
                VALUES ('default', ?, 'cluster-a', 'siem-events', 'single_event', 0,
                        1, '{"schemaVersion":"1","tenantId":"default","targetCluster":"cluster-a","jobGroupKey":"x","generation":1,"members":[]}', 'hash', ?, ?)
                """, groupKey, status, Timestamp.from(Instant.now().minusSeconds(2)));
    }

    private String state() {
        return jdbc.queryForObject("SELECT reconcile_state FROM detection_job_group", String.class);
    }
}
