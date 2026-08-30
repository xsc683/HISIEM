package com.xscsiem.hsiem_platform.detection.controller;

import com.xscsiem.hsiem_platform.detection.runtime.FlinkRuntimePort;
import com.xscsiem.hsiem_platform.detection.runtime.process.ProcessFlinkRuntimeAdapter;
import com.xscsiem.hsiem_platform.detection.runtime.process.ProcessFlinkRuntimeConfiguration;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.support.TestPropertySourceUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DetectionRuntimeAdapterSelectionTest {

    @Test
    void missingAdapterPropertySelectsOnlyDisabledPort() {
        try (AnnotationConfigApplicationContext context = context()) {
            Map<String, FlinkRuntimePort> ports = context.getBeansOfType(FlinkRuntimePort.class);
            assertEquals(1, ports.size());
            assertInstanceOf(DisabledFlinkRuntimePort.class, ports.values().iterator().next());
            assertFalse(context.containsBean("processFlinkRuntimeAdapter"));
        }
    }

    @Test
    void processAdapterPropertySelectsOnlyProcessPort() {
        try (AnnotationConfigApplicationContext context = context("app.detection.runtime-adapter=process")) {
            Map<String, FlinkRuntimePort> ports = context.getBeansOfType(FlinkRuntimePort.class);
            assertEquals(1, ports.size());
            assertInstanceOf(ProcessFlinkRuntimeAdapter.class, ports.values().iterator().next());
            assertTrue(context.containsBean("processFlinkRuntimeAdapter"));
            assertFalse(context.containsBean("disabledFlinkRuntimePort"));
        }
    }

    private AnnotationConfigApplicationContext context(String... properties) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getDefaultListableBeanFactory().setConversionService(
                ApplicationConversionService.getSharedInstance());
        if (properties.length > 0) {
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context, properties);
        }
        context.register(TestDataSourceConfiguration.class, DisabledFlinkRuntimePort.class,
                ProcessFlinkRuntimeConfiguration.class);
        context.refresh();
        return context;
    }

    @Configuration(proxyBeanMethods = false)
    static class TestDataSourceConfiguration {
        @Bean
        HikariDataSource dataSource() {
            HikariDataSource dataSource = new HikariDataSource();
            dataSource.setJdbcUrl("jdbc:h2:mem:selection-" + System.nanoTime()
                    + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
            dataSource.setUsername("sa");
            dataSource.setPassword("");
            return dataSource;
        }

        @Bean
        JdbcTemplate jdbcTemplate(HikariDataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }
    }
}
