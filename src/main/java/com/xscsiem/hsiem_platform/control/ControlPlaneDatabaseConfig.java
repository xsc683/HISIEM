package com.xscsiem.hsiem_platform.control;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Spring Boot 4.1 当前不自动装配 Flyway，因此在应用配置层显式执行迁移。
 * Flyway bean 初始化完成后，JDBC 控制面存储才会创建，保证不会先读未建表的数据库。
 */
@Configuration
public class ControlPlaneDatabaseConfig {

    @Bean
    public Flyway flyway(DataSource dataSource,
                         @Value("${spring.flyway.locations:classpath:db/migration}") String locations) {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations(locations)
                .validateOnMigrate(true)
                .load();
        flyway.migrate();
        return flyway;
    }
}
