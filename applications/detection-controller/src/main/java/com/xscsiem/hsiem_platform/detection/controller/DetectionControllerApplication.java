package com.xscsiem.hsiem_platform.detection.controller;

import com.xscsiem.hsiem_platform.rules.runtime.DetectionRuntimeRepository;
import com.xscsiem.hsiem_platform.rules.runtime.DetectionRuntimeService;
import com.xscsiem.hsiem_platform.detection.runtime.process.ProcessFlinkRuntimeConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Independent composition root; this process has no HTTP/control-api dependency. */
@SpringBootApplication
@EnableScheduling
@Import({DetectionRuntimeService.class, DetectionRuntimeRepository.class,
        ProcessFlinkRuntimeConfiguration.class})
public class DetectionControllerApplication {

    public static void main(String[] args) {
        new SpringApplicationBuilder(DetectionControllerApplication.class)
                .web(WebApplicationType.NONE)
                .run(args);
    }
}
