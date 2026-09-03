package com.xscsiem.hsiem_platform;

import com.xscsiem.hsiem_platform.rules.ManagedDetectionMapper;
import com.xscsiem.hsiem_platform.rules.runtime.DetectionRuntimeMapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@MapperScan(basePackageClasses = {ManagedDetectionMapper.class, DetectionRuntimeMapper.class})
public class HsiemPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(HsiemPlatformApplication.class, args);
    }
}
