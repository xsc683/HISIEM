package com.xscsiem.hsiem_platform.control;

import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * 控制面 MyBatis mapper 资源配置。iam 的控制面 XML 单独用一个工厂解析, 与 detection 域 {@code mybatis/detection/*.xml} 解耦;
 * 各应用组合根用 {@code @MapperScan(basePackageClasses=...,
 * sqlSessionFactoryRef="controlPlaneSqlSessionFactory")} 只扫描本应用需要的控制面 mapper。
 */
@Configuration(proxyBeanMethods = false)
public class ControlPlaneMyBatisConfiguration {

    /** Bean name used as the {@code sqlSessionFactoryRef} by application composition roots. */
    public static final String SESSION_FACTORY_NAME = "controlPlaneSqlSessionFactory";

    /**
     * Primary so the mybatis starter's shared {@code sqlSessionTemplate} auto-config bean can
     * resolve a single factory when an app also defines a detection factory.
     */
    @Bean(name = SESSION_FACTORY_NAME)
    @Primary
    SqlSessionFactory controlPlaneSqlSessionFactory(DataSource dataSource) throws Exception {
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setMapperLocations(
                new PathMatchingResourcePatternResolver()
                        .getResources("classpath*:mybatis/control/*.xml"));
        return factory.getObject();
    }
}
