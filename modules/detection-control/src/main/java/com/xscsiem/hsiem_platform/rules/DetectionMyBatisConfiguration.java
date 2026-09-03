package com.xscsiem.hsiem_platform.rules;

import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/** Explicit mapper resource configuration shared by both application composition roots. */
@Configuration(proxyBeanMethods = false)
public class DetectionMyBatisConfiguration {
    @Bean
    SqlSessionFactory detectionSqlSessionFactory(DataSource dataSource) throws Exception {
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setTypeHandlers(new UuidTypeHandler());
        factory.setMapperLocations(
                new PathMatchingResourcePatternResolver()
                        .getResources("classpath*:mybatis/detection/*.xml"));
        return factory.getObject();
    }
}
