package com.xscsiem.hsiem_platform.soar;

import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

/** PostgreSQL 行锁保护的跨实例速率、日配额、并发计数和熔断状态。 */
@Component
@DependsOn("flyway")
public class SoarConnectorGuard {

    private final JdbcTemplate jdbc;

    public SoarConnectorGuard(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Permit acquire(String tenantId, SoarConnector connector, Duration timeout) {
        SoarConnector.Limits limits = limits(connector);
        Instant now = Instant.now();
        ensureRow(tenantId, connector.id(), now);
        RuntimeState state = jdbc.queryForObject("""
                SELECT * FROM soar_connector_runtime WHERE tenant_id = ? AND connector_id = ? FOR UPDATE
                """, (rs, rowNum) -> new RuntimeState(
                rs.getTimestamp("window_started_at").toInstant(), rs.getInt("window_calls"),
                rs.getString("daily_bucket"), rs.getInt("daily_calls"),
                rs.getInt("consecutive_failures"), instant(rs.getTimestamp("circuit_open_until")),
                rs.getBoolean("probe_in_flight"), rs.getInt("in_flight"),
                instant(rs.getTimestamp("in_flight_expires_at"))), tenantId, connector.id());
        int windowCalls = state.windowStartedAt().isBefore(now.minusSeconds(60)) ? 0 : state.windowCalls();
        Instant windowStart = windowCalls == 0 ? now : state.windowStartedAt();
        String day = LocalDate.now(java.time.ZoneOffset.UTC).toString();
        int dailyCalls = day.equals(state.dailyBucket()) ? state.dailyCalls() : 0;
        int inFlight = state.inFlightExpiresAt() != null && state.inFlightExpiresAt().isBefore(now)
                ? 0 : state.inFlight();
        boolean probe = state.probeInFlight();
        if (state.circuitOpenUntil() != null && state.circuitOpenUntil().isAfter(now)) {
            throw new ConnectorRejectedException("CIRCUIT_OPEN", "连接器熔断中");
        }
        if (state.circuitOpenUntil() != null && !state.circuitOpenUntil().isAfter(now)) {
            if (probe) throw new ConnectorRejectedException("HALF_OPEN_BUSY", "连接器半开探测正在执行");
            probe = true;
        }
        if (windowCalls >= limits.perMinute()) throw new ConnectorRejectedException("RATE_LIMIT", "连接器分钟限流");
        if (dailyCalls >= limits.perDay()) throw new ConnectorRejectedException("DAILY_QUOTA", "连接器日配额耗尽");
        if (inFlight >= limits.maxConcurrent()) throw new ConnectorRejectedException("BULKHEAD_FULL", "连接器并发舱已满");
        Instant inFlightExpiry = now.plus(timeout).plusSeconds(5);
        jdbc.update("""
                UPDATE soar_connector_runtime SET window_started_at = ?, window_calls = ?,
                    daily_bucket = ?, daily_calls = ?, probe_in_flight = ?, in_flight = ?,
                    in_flight_expires_at = ?, updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND connector_id = ?
                """, Timestamp.from(windowStart), windowCalls + 1, day, dailyCalls + 1,
                probe, inFlight + 1, Timestamp.from(inFlightExpiry), tenantId, connector.id());
        return new Permit(tenantId, connector.id(), probe);
    }

    @Transactional
    public void success(Permit permit, String operation, String executionId, long durationMs) {
        jdbc.update("""
                UPDATE soar_connector_runtime SET consecutive_failures = 0, circuit_open_until = NULL,
                    probe_in_flight = FALSE, in_flight = CASE WHEN in_flight > 0 THEN in_flight - 1 ELSE 0 END,
                    updated_at = CURRENT_TIMESTAMP WHERE tenant_id = ? AND connector_id = ?
                """, permit.tenantId(), permit.connectorId());
        invocation(permit, operation, executionId, "success", durationMs, null);
    }

    @Transactional
    public void failure(Permit permit, SoarConnector connector, String operation,
                        String executionId, long durationMs, String errorCode) {
        SoarConnector.Limits limits = limits(connector);
        Integer failures = jdbc.queryForObject("""
                SELECT consecutive_failures FROM soar_connector_runtime
                WHERE tenant_id = ? AND connector_id = ? FOR UPDATE
                """, Integer.class, permit.tenantId(), permit.connectorId());
        int next = (failures == null ? 0 : failures) + 1;
        Instant openUntil = next >= limits.failureThreshold()
                ? Instant.now().plusSeconds(limits.circuitOpenSeconds()) : null;
        jdbc.update("""
                UPDATE soar_connector_runtime SET consecutive_failures = ?, circuit_open_until = ?,
                    probe_in_flight = FALSE, in_flight = CASE WHEN in_flight > 0 THEN in_flight - 1 ELSE 0 END,
                    updated_at = CURRENT_TIMESTAMP WHERE tenant_id = ? AND connector_id = ?
                """, next, openUntil == null ? null : Timestamp.from(openUntil),
                permit.tenantId(), permit.connectorId());
        invocation(permit, operation, executionId, "failure", durationMs, errorCode);
    }

    public java.util.List<java.util.Map<String, Object>> status(String tenantId) {
        return jdbc.queryForList("""
                SELECT connector_id AS "connectorId", window_calls AS "windowCalls",
                    daily_calls AS "dailyCalls", consecutive_failures AS "consecutiveFailures",
                    circuit_open_until AS "circuitOpenUntil", in_flight AS "inFlight",
                    updated_at AS "updatedAt" FROM soar_connector_runtime
                WHERE tenant_id = ? ORDER BY connector_id
                """, tenantId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void rejected(String tenantId, String connectorId, String operation,
                         String executionId, String errorCode) {
        jdbc.update("""
                INSERT INTO soar_connector_invocations(tenant_id, connector_id, operation,
                    execution_id, outcome, duration_ms, error_code) VALUES (?, ?, ?, ?, 'rejected', 0, ?)
                """, tenantId, connectorId, operation, executionId, errorCode);
    }

    private void ensureRow(String tenantId, String connectorId, Instant now) {
        jdbc.queryForObject("SELECT id FROM tenants WHERE id = ? FOR UPDATE", String.class, tenantId);
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM soar_connector_runtime WHERE tenant_id = ? AND connector_id = ?
                """, Integer.class, tenantId, connectorId);
        if (count != null && count > 0) return;
        jdbc.update("""
                INSERT INTO soar_connector_runtime(tenant_id, connector_id, window_started_at, daily_bucket)
                VALUES (?, ?, ?, ?)
                """, tenantId, connectorId, Timestamp.from(now), LocalDate.now(java.time.ZoneOffset.UTC).toString());
    }

    private void invocation(Permit permit, String operation, String executionId, String outcome,
                            long durationMs, String errorCode) {
        jdbc.update("""
                INSERT INTO soar_connector_invocations(tenant_id, connector_id, operation,
                    execution_id, outcome, duration_ms, error_code) VALUES (?, ?, ?, ?, ?, ?, ?)
                """, permit.tenantId(), permit.connectorId(), operation, executionId,
                outcome, durationMs, errorCode);
    }

    private static SoarConnector.Limits limits(SoarConnector connector) {
        SoarConnector.Limits value = connector.limits();
        return new SoarConnector.Limits(value == null || value.perMinute() == null ? 60 : value.perMinute(),
                value == null || value.perDay() == null ? 5000 : value.perDay(),
                value == null || value.maxConcurrent() == null ? 4 : value.maxConcurrent(),
                value == null || value.failureThreshold() == null ? 5 : value.failureThreshold(),
                value == null || value.circuitOpenSeconds() == null ? 60 : value.circuitOpenSeconds());
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    public record Permit(String tenantId, String connectorId, boolean halfOpenProbe) {
    }

    private record RuntimeState(Instant windowStartedAt, int windowCalls, String dailyBucket,
                                int dailyCalls, int consecutiveFailures, Instant circuitOpenUntil,
                                boolean probeInFlight, int inFlight, Instant inFlightExpiresAt) {
    }

    public static class ConnectorRejectedException extends IllegalStateException {
        private final String code;

        public ConnectorRejectedException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
