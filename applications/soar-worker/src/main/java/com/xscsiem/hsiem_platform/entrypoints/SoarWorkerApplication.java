package com.xscsiem.hsiem_platform.entrypoints;

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
import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
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
            LifecycleOutboxMapper.class
        },
        annotationClass = Mapper.class,
        sqlSessionFactoryRef = ControlPlaneMyBatisConfiguration.SESSION_FACTORY_NAME)
@MapperScan(
        basePackageClasses = SoarMapper.class,
        annotationClass = Mapper.class,
        sqlSessionFactoryRef = SoarMyBatisConfiguration.SESSION_FACTORY_NAME)
public class SoarWorkerApplication {

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
