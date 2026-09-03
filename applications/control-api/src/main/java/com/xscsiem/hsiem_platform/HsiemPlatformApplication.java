package com.xscsiem.hsiem_platform;

import com.xscsiem.hsiem_platform.control.ControlPlaneMyBatisConfiguration;
import com.xscsiem.hsiem_platform.rules.ManagedDetectionMapper;
import com.xscsiem.hsiem_platform.rules.runtime.DetectionRuntimeMapper;
import com.xscsiem.hsiem_platform.soar.persistence.SoarMapper;
import com.xscsiem.hsiem_platform.soar.persistence.SoarMyBatisConfiguration;
import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@MapperScan(
        basePackageClasses = {ManagedDetectionMapper.class, DetectionRuntimeMapper.class},
        sqlSessionFactoryRef = "detectionSqlSessionFactory")
@MapperScan(
        basePackages = {"com.xscsiem.hsiem_platform.control", "com.xscsiem.hsiem_platform.tenant"},
        annotationClass = Mapper.class,
        sqlSessionFactoryRef = ControlPlaneMyBatisConfiguration.SESSION_FACTORY_NAME)
@MapperScan(
        basePackageClasses = SoarMapper.class,
        annotationClass = Mapper.class,
        sqlSessionFactoryRef = SoarMyBatisConfiguration.SESSION_FACTORY_NAME)
public class HsiemPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(HsiemPlatformApplication.class, args);
    }
}
