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
        assertEquals(5, jdbc.queryForObject(
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
    }
}
