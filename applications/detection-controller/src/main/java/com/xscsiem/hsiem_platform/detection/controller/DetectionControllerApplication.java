package com.xscsiem.hsiem_platform.detection.controller;

import com.xscsiem.hsiem_platform.detection.runtime.DetectionArtifactMapper;
import com.xscsiem.hsiem_platform.detection.runtime.process.ProcessFlinkRuntimeConfiguration;
import com.xscsiem.hsiem_platform.rules.ManagedDetectionMapper;
import com.xscsiem.hsiem_platform.rules.runtime.DetectionRuntimeMapper;
import com.xscsiem.hsiem_platform.rules.runtime.DetectionRuntimeService;
import com.xscsiem.hsiem_platform.rules.runtime.MyBatisDetectionRuntimeRepository;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Independent composition root; this process has no HTTP/control-api dependency. */
@SpringBootApplication
@EnableScheduling
@MapperScan(
        basePackageClasses = {
            ManagedDetectionMapper.class,
            DetectionArtifactMapper.class,
            DetectionRuntimeMapper.class,
            DetectionControllerMapper.class
        })
@Import({
    DetectionRuntimeService.class,
    MyBatisDetectionRuntimeRepository.class,
    MyBatisDetectionControllerRepository.class,
    ProcessFlinkRuntimeConfiguration.class
})
public class DetectionControllerApplication {

    public static void main(String[] args) {
        new SpringApplicationBuilder(DetectionControllerApplication.class)
                .web(WebApplicationType.NONE)
                .run(args);
    }
}
