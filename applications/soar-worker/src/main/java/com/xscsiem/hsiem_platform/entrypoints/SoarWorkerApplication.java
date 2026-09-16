package com.xscsiem.hsiem_platform.entrypoints;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xscsiem.hsiem_platform.control.CaseMapper;
import com.xscsiem.hsiem_platform.control.CaseMirrorOutboxMapper;
import com.xscsiem.hsiem_platform.control.ControlPlaneMyBatisConfiguration;
import com.xscsiem.hsiem_platform.control.LifecycleOutboxMapper;
import com.xscsiem.hsiem_platform.control.NotificationMapper;
import com.xscsiem.hsiem_platform.control.RoleAuditMapper;
import com.xscsiem.hsiem_platform.control.TaskMapper;
import com.xscsiem.hsiem_platform.control.UserAuthMapper;
import com.xscsiem.hsiem_platform.soar.persistence.SoarMapper;
import com.xscsiem.hsiem_platform.soar.persistence.SoarMyBatisConfiguration;
import com.xscsiem.hsiem_platform.tenant.TenantMapper;
import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Standalone non-HTTP composition root for SOAR execution and lifecycle consumption. */
@SpringBootApplication(scanBasePackages = "com.xscsiem.hsiem_platform")
@EnableScheduling
@MapperScan(
        basePackageClasses = {
            UserAuthMapper.class,
            RoleAuditMapper.class,
            NotificationMapper.class,
            CaseMapper.class,
            CaseMirrorOutboxMapper.class,
            TaskMapper.class,
            LifecycleOutboxMapper.class,
            // AuthService (component-scanned from com.xscsiem.hsiem_platform) requires
            // TenantService -> MyBatisTenantRepository -> TenantMapper. Without this the
            // worker context cannot start at all; control-api scans the same package.
            TenantMapper.class
        },
        annotationClass = Mapper.class,
        sqlSessionFactoryRef = ControlPlaneMyBatisConfiguration.SESSION_FACTORY_NAME)
@MapperScan(
        basePackageClasses = SoarMapper.class,
        annotationClass = Mapper.class,
        sqlSessionFactoryRef = SoarMyBatisConfiguration.SESSION_FACTORY_NAME)
public class SoarWorkerApplication {

    /**
     * Jackson 2 ObjectMapper for the component-scanned beans that require one.
     *
     * <p>Spring Boot 4 auto-configures Jackson 3 ({@code tools.jackson}), and the Jackson 2 {@code
     * com.fasterxml.jackson.databind.ObjectMapper} bean is only auto-configured as part of the HTTP
     * message converters of a web application. This worker is {@code WebApplicationType.NONE} but
     * still component-scans {@code com.xscsiem.hsiem_platform}, which pulls in beans that take a
     * Jackson 2 ObjectMapper ({@code AlertService -> ElasticsearchGateway}). Without this bean the
     * worker context cannot start at all.
     */
    @Bean
    ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    public static void main(String[] args) {
        new SpringApplicationBuilder(SoarWorkerApplication.class)
                .web(WebApplicationType.NONE)
                .properties(
                        "app.process-role=soar-worker",
                        "app.soar.runtime-enabled=true",
                        "app.soar.kafka-consumer-enabled=true",
                        "app.operations.runtime-enabled=false")
                .run(args);
    }
}
