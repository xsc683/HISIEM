package com.xscsiem.hsiem_platform.investigation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xscsiem.hsiem_platform.control.ControlPlaneStore;
import com.xscsiem.hsiem_platform.search.ElasticsearchGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 将 PostgreSQL 案件事实源可靠投递到 ES 镜像。数据库 outbox 保证进程重启后仍可重试，
 * lease + 事务行锁保证多实例不会同时处理同一条消息；当前领取 SQL 不使用 SKIP LOCKED。
 */
@Component
@ConditionalOnProperty(name = "app.operations.runtime-enabled", havingValue = "true", matchIfMissing = true)
public class CaseMirrorDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(CaseMirrorDispatcher.class);
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final ControlPlaneStore control;
    private final ElasticsearchGateway elasticsearch;
    private final ObjectMapper mapper;
    private final String owner = "case-mirror-" + UUID.randomUUID();
    private final Duration lease;

    public CaseMirrorDispatcher(ControlPlaneStore control, ElasticsearchGateway elasticsearch,
                                ObjectMapper mapper,
                                @Value("${app.cases.outbox-lease:PT2M}") Duration lease) {
        this.control = control;
        this.elasticsearch = elasticsearch;
        this.mapper = mapper;
        this.lease = lease;
    }

    @Scheduled(initialDelayString = "${app.cases.outbox-initial-delay-ms:5000}",
            fixedDelayString = "${app.cases.outbox-interval-ms:5000}")
    public void dispatch() {
        List<Map<String, Object>> batch = control.claimCaseMirrorBatch(owner, Instant.now().plus(lease), 50);
        for (Map<String, Object> item : batch) {
            long id = ((Number) item.get("id")).longValue();
            String caseId = String.valueOf(item.get("caseId"));
            try {
                String operation = String.valueOf(item.get("operation"));
                ElasticsearchGateway.Response response;
                if ("delete".equals(operation)) {
                    response = elasticsearch.request("DELETE", "/siem-cases/_doc/" + caseId, null);
                    if ((response.code() < 200 || response.code() >= 300) && response.code() != 404) {
                        throw new IllegalStateException("ES 删除案件失败 HTTP " + response.code());
                    }
                } else {
                    Map<String, Object> document = mapper.readValue(String.valueOf(item.get("payload")), MAP);
                    document.remove("_id");
                    document.remove("_control_version");
                    response = elasticsearch.request("PUT", "/siem-cases/_doc/" + caseId + "?refresh=false",
                            mapper.writeValueAsString(document));
                    if (response.code() < 200 || response.code() >= 300) {
                        throw new IllegalStateException("ES 更新案件失败 HTTP " + response.code());
                    }
                }
                control.completeCaseMirror(id, owner, true, null, Instant.now());
            } catch (Exception e) {
                Instant retryAt = Instant.now().plusSeconds(Math.min(300, 2L << Math.min(7,
                        ((Number) item.getOrDefault("attempts", 0)).intValue())));
                control.completeCaseMirror(id, owner, false, safe(e), retryAt);
                LOG.warn("案件镜像 outbox 投递失败 caseId={},将于 {} 重试: {}", caseId, retryAt, e.getMessage());
            }
        }
    }

    private static String safe(Exception e) {
        String value = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        value = value.replace("\u0000", "");
        return value.substring(0, Math.min(4000, value.length()));
    }
}
