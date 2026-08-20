package com.xscsiem.hsiem_platform.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/** 将 ES 连通性纳入 Actuator readiness/health。 */
@Component("elasticsearch")
public class ElasticsearchHealthIndicator implements HealthIndicator {

    private final ElasticsearchClient client;

    public ElasticsearchHealthIndicator(ElasticsearchClient client) {
        this.client = client;
    }

    @Override
    public Health health() {
        try {
            if (client.ping().value()) {
                return Health.up().withDetail("service", "elasticsearch").build();
            }
            return Health.down().withDetail("service", "elasticsearch").build();
        } catch (Exception e) {
            return Health.down(e).withDetail("service", "elasticsearch").build();
        }
    }
}
