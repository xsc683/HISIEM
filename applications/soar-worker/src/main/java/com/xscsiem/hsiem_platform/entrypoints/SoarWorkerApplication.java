package com.xscsiem.hsiem_platform.entrypoints;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Standalone non-HTTP composition root for SOAR execution and lifecycle consumption. */
@SpringBootApplication(scanBasePackages = "com.xscsiem.hsiem_platform")
@EnableScheduling
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
