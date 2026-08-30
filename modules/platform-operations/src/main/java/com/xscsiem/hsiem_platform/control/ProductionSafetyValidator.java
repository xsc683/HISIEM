package com.xscsiem.hsiem_platform.control;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/** 生产模式 fail-closed：禁止控制面以明文 ES/Kafka 或空凭据启动。 */
@Component
public class ProductionSafetyValidator {

    @Value("${app.production-mode:false}")
    private boolean production;

    @Value("${app.elasticsearch.url:http://localhost:9200}")
    private String elasticsearchUrl;

    @Value("${app.elasticsearch.username:}")
    private String elasticsearchUsername;

    @Value("${app.elasticsearch.password:}")
    private String elasticsearchPassword;

    @Value("${app.health.scan.kafka-security-protocol:PLAINTEXT}")
    private String kafkaSecurityProtocol;

    @PostConstruct
    void validate() {
        if (!production) return;
        if (!elasticsearchUrl.startsWith("https://")) {
            throw new IllegalStateException("生产模式要求 SIEM_ES_URL 使用 https://");
        }
        if (elasticsearchUsername.isBlank() || elasticsearchPassword.isBlank()) {
            throw new IllegalStateException("生产模式要求 SIEM_ES_USERNAME/SIEM_ES_PASSWORD");
        }
        if (!"SASL_SSL".equalsIgnoreCase(kafkaSecurityProtocol)) {
            throw new IllegalStateException("生产模式要求 SIEM_KAFKA_SECURITY_PROTOCOL=SASL_SSL");
        }
    }
}
