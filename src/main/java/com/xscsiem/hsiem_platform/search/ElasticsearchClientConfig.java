package com.xscsiem.hsiem_platform.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Elasticsearch Java API Client 配置。所有生产 ES 请求共享连接池和超时。 */
@Configuration
public class ElasticsearchClientConfig {

    @Bean(destroyMethod = "close")
    RestClient elasticsearchRestClient(
            @Value("${app.elasticsearch.url:http://localhost:9200}") String url,
            @Value("${app.elasticsearch.username:}") String username,
            @Value("${app.elasticsearch.password:}") String password) {
        RestClientBuilder builder = RestClient.builder(HttpHost.create(url));
        if (!username.isBlank()) {
            BasicCredentialsProvider credentials = new BasicCredentialsProvider();
            credentials.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(username, password));
            builder.setHttpClientConfigCallback(http -> http.setDefaultCredentialsProvider(credentials));
        }
        return builder.build();
    }

    @Bean
    ElasticsearchTransport elasticsearchTransport(RestClient restClient) {
        return new RestClientTransport(restClient, new JacksonJsonpMapper());
    }

    @Bean
    ElasticsearchClient elasticsearchClient(ElasticsearchTransport transport) {
        return new ElasticsearchClient(transport);
    }
}
