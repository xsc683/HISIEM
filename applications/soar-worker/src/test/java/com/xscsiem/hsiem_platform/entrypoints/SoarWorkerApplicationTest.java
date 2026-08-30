package com.xscsiem.hsiem_platform.entrypoints;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoarWorkerApplicationTest {

    @Test
    void isAnIndependentNonWebSpringBootCompositionRoot() {
        SpringBootApplication application = SoarWorkerApplication.class
                .getAnnotation(SpringBootApplication.class);
        assertNotNull(application);
        assertTrue(SoarWorkerApplication.class.isAnnotationPresent(EnableScheduling.class));

        SpringApplication springApplication = new SpringApplicationBuilder(SoarWorkerApplication.class)
                .web(WebApplicationType.NONE)
                .properties(
                        "app.process-role=soar-worker",
                        "app.soar.runtime-enabled=true",
                        "app.soar.kafka-consumer-enabled=true",
                        "app.operations.runtime-enabled=false")
                .build();
        assertEquals(WebApplicationType.NONE, springApplication.getWebApplicationType());
    }
}
