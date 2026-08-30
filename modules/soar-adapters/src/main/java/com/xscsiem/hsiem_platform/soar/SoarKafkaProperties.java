package com.xscsiem.hsiem_platform.soar;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.config.SslConfigs;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Properties;

@Component
public class SoarKafkaProperties {

    private final String bootstrap;
    private final String group;
    private final String alertTopic;
    private final String caseTopic;
    private final String securityProtocol;
    private final String saslMechanism;
    private final String saslJaasConfig;
    private final String truststoreLocation;
    private final String truststorePassword;

    public SoarKafkaProperties(
            @Value("${app.soar.kafka-bootstrap:localhost:9092}") String bootstrap,
            @Value("${app.soar.kafka-group:siem-soar-runtime}") String group,
            @Value("${app.soar.alert-topic:siem-alert-lifecycle}") String alertTopic,
            @Value("${app.soar.case-topic:siem-case-lifecycle}") String caseTopic,
            @Value("${app.soar.kafka-security-protocol:PLAINTEXT}") String securityProtocol,
            @Value("${app.soar.kafka-sasl-mechanism:}") String saslMechanism,
            @Value("${app.soar.kafka-sasl-jaas-config:}") String saslJaasConfig,
            @Value("${app.soar.kafka-ssl-truststore-location:}") String truststoreLocation,
            @Value("${app.soar.kafka-ssl-truststore-password:}") String truststorePassword) {
        this.bootstrap = bootstrap;
        this.group = group;
        this.alertTopic = alertTopic;
        this.caseTopic = caseTopic;
        this.securityProtocol = securityProtocol;
        this.saslMechanism = saslMechanism;
        this.saslJaasConfig = saslJaasConfig;
        this.truststoreLocation = truststoreLocation;
        this.truststorePassword = truststorePassword;
    }

    public Properties producer() {
        Properties value = common();
        value.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        value.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        value.put(ProducerConfig.ACKS_CONFIG, "all");
        value.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        value.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, "10000");
        value.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, "5000");
        return value;
    }

    public Properties consumer() {
        Properties value = common();
        value.put(ConsumerConfig.GROUP_ID_CONFIG, group);
        value.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        value.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        value.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        value.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        value.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "100");
        return value;
    }

    public Properties admin() {
        Properties value = common();
        value.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "3000");
        value.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "5000");
        return value;
    }

    public List<String> topics() {
        return List.of(alertTopic, caseTopic);
    }

    public String topicFor(String objectType) {
        return "case".equals(objectType) ? caseTopic : alertTopic;
    }

    public String group() {
        return group;
    }

    private Properties common() {
        Properties value = new Properties();
        value.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        value.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, securityProtocol);
        putIfPresent(value, SaslConfigs.SASL_MECHANISM, saslMechanism);
        putIfPresent(value, SaslConfigs.SASL_JAAS_CONFIG, saslJaasConfig);
        putIfPresent(value, SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG, truststoreLocation);
        putIfPresent(value, SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG, truststorePassword);
        return value;
    }

    private void putIfPresent(Properties properties, String key, String value) {
        if (value != null && !value.isBlank()) properties.put(key, value);
    }
}
