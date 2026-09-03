package com.xscsiem.hsiem_platform.soar.persistence;

import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * SOAR MyBatis mapper resource configuration. Lives in soar-core so both application composition
 * roots (control-api, soar-worker) discover it through their {@code com.xscsiem.hsiem_platform}
 * component scan without a control-API-to-soar or detection dependency. Each app adds a
 * {@code @MapperScan(basePackageClasses = SoarMapper.class, annotationClass = Mapper.class,
 * sqlSessionFactoryRef = "soarSqlSessionFactory")} for the SOAR mappers it needs.
 *
 * <p>Not {@code @Primary}: the control-plane factory already carries that role so the mybatis
 * starter's shared {@code sqlSessionTemplate} keeps resolving to it; SOAR mappers are bound to this
 * factory explicitly.
 */
@Configuration(proxyBeanMethods = false)
public class SoarMyBatisConfiguration {

    /** Bean name used as the {@code sqlSessionFactoryRef} by application composition roots. */
    public static final String SESSION_FACTORY_NAME = "soarSqlSessionFactory";

    @Bean(name = SESSION_FACTORY_NAME)
    SqlSessionFactory soarSqlSessionFactory(DataSource dataSource) throws Exception {
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setMapperLocations(
                new PathMatchingResourcePatternResolver()
                        .getResources("classpath*:mybatis/soar/*.xml"));
        return factory.getObject();
    }
}
