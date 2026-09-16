package com.xscsiem.hsiem_platform.soar;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * 内部服务到服务 SOAR 端点:走完整安全链(H2 PostgreSQL 模式),复用真实持久化执行。 镜像 {@link SoarRuntimeIntegrationTest} 的装配方式。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class InternalSoarControllerIntegrationTest {

    private static final String TOKEN = "test-internal-service-credential";
    private static final String TENANT = "default";

    @Autowired private MockMvc mvc;
    @Autowired private SoarService service;
    @Autowired private SoarStore store;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void trustedRequestTriggersPublishedPlaybookAndReusesIdempotencyKey() throws Exception {
        SoarPlaybook playbook = published("alert", List.of("alert.created"));

        String first =
                mvc.perform(triggerRequest(playbook.id(), "alert-42", "copilot-run-1", TENANT))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.execution_id").isString())
                        .andExpect(jsonPath("$.status").value("pending"))
                        .andExpect(jsonPath("$.result").isMap())
                        .andExpect(jsonPath("$.error_code").value(nullValue()))
                        .andExpect(jsonPath("$.error_message").value(nullValue()))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String firstId = JsonPath.read(first, "$.execution_id");

        String second =
                mvc.perform(triggerRequest(playbook.id(), "alert-42", "copilot-run-1", TENANT))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.execution_id").value(firstId))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertEquals(firstId, JsonPath.read(second, "$.execution_id"));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM soar_execution WHERE playbook_id = ? "
                                + "AND trigger_message_id = ?",
                        Integer.class,
                        playbook.id(),
                        "copilot-run-1"));

        SoarExecution execution = store.getExecution(TENANT, firstId);
        assertEquals("manual:copilot", execution.triggerEnvelope().producer());
        assertEquals("MANUAL", execution.triggerType());
        assertEquals("alert.created", execution.eventType());
        // payload 只带资源引用 + objectId,不含机密。
        assertEquals(
                java.util.Map.of(
                        "alert",
                        java.util.Map.of(
                                "provider",
                                "hisiem",
                                "resource_type",
                                "alert",
                                "address_id",
                                "alert-42",
                                "id",
                                "alert-42")),
                execution.payloadSnapshot());
    }

    @Test
    void sameIdempotencyKeyWithADifferentContractIsAConflictNotAWrongTarget() throws Exception {
        SoarPlaybook playbook = published("alert", List.of("alert.created"));
        String key = "copilot-fixed-key";
        String firstId =
                JsonPath.read(
                        mvc.perform(triggerRequest(playbook.id(), "alert-42", key, TENANT))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.execution_id").isString())
                                .andReturn()
                                .getResponse()
                                .getContentAsString(),
                        "$.execution_id");

        // (a) Replaying the SAME immutable contract converges on the same execution.
        mvc.perform(triggerRequest(playbook.id(), "alert-42", key, TENANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.execution_id").value(firstId));

        // (b) The SAME key with a DIFFERENT target must NOT silently return the first
        // execution — that would land the operator's approved action on the wrong alert.
        mvc.perform(triggerRequest(playbook.id(), "alert-99", key, TENANT))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        assertEquals(1, executionCount(playbook.id(), key), "冲突请求不得产生新的执行");

        // (c) The first execution is untouched: still pointed at the originally approved alert.
        assertEquals("alert-42", store.getExecution(TENANT, firstId).objectId());
    }

    private int executionCount(String playbookId, String messageId) {
        Integer count =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM soar_execution WHERE playbook_id = ? "
                                + "AND trigger_message_id = ?",
                        Integer.class,
                        playbookId,
                        messageId);
        return count == null ? 0 : count;
    }

    @Test
    void missingBearerIsUnauthorized() throws Exception {
        mvc.perform(
                        post("/api/internal/soar/executions")
                                .header("X-Tenant-ID", TENANT)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body("pb-missing", "alert-1")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void wrongBearerIsUnauthorized() throws Exception {
        mvc.perform(
                        post("/api/internal/soar/executions")
                                .header("Authorization", "Bearer wrong-token")
                                .header("X-Tenant-ID", TENANT)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body("pb-missing", "alert-1")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void missingOrBlankTenantIsRejected() throws Exception {
        mvc.perform(
                        post("/api/internal/soar/executions")
                                .header("Authorization", "Bearer " + TOKEN)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body("pb-missing", "alert-1")))
                .andExpect(status().isUnauthorized());
        mvc.perform(
                        post("/api/internal/soar/executions")
                                .header("Authorization", "Bearer " + TOKEN)
                                .header("X-Tenant-ID", "   ")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body("pb-missing", "alert-1")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unsupportedActionKeyIsBadRequest() throws Exception {
        mvc.perform(
                        post("/api/internal/soar/executions")
                                .header("Authorization", "Bearer " + TOKEN)
                                .header("X-Tenant-ID", TENANT)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"action_key\":\"DELETE_EVERYTHING\",\"playbook_id\":\"pb-x\","
                                                + "\"target\":{\"provider\":\"hisiem\",\"resource_type\":\"alert\","
                                                + "\"address_id\":\"alert-1\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void unknownPlaybookIsNotFoundAndNotPublishedIsConflict() throws Exception {
        mvc.perform(triggerRequest("pb-does-not-exist", "alert-1", "copilot-missing", TENANT))
                .andExpect(status().isNotFound());
        SoarPlaybook draft =
                service.createPlaybook(
                        "草稿", "not published", "alert", List.of("alert.created"), "admin");
        mvc.perform(triggerRequest(draft.id(), "alert-1", "copilot-draft", TENANT))
                .andExpect(status().isConflict());
    }

    @Test
    void getReturnsCurrentStatusAndWrongTenantIsNotFound() throws Exception {
        SoarPlaybook playbook = published("alert", List.of("alert.created"));
        String id =
                JsonPath.read(
                        mvc.perform(triggerRequest(playbook.id(), "alert-7", "copilot-get", TENANT))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString(),
                        "$.execution_id");

        mvc.perform(
                        get("/api/internal/soar/executions/" + id)
                                .header("Authorization", "Bearer " + TOKEN)
                                .header("X-Tenant-ID", TENANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.execution_id").value(id))
                .andExpect(jsonPath("$.status").value("pending"))
                .andExpect(jsonPath("$.error_code").value(nullValue()));

        mvc.perform(
                        get("/api/internal/soar/executions/" + id)
                                .header("Authorization", "Bearer " + TOKEN)
                                .header("X-Tenant-ID", "other-tenant"))
                .andExpect(status().isNotFound());
    }

    private SoarPlaybook published(String objectType, List<String> eventTypes) {
        SoarPlaybook created =
                service.createPlaybook("内部触发", "internal service", objectType, eventTypes, "admin");
        return service.publishPlaybook(created.id(), created.revision(), "admin");
    }

    private MockHttpServletRequestBuilder triggerRequest(
            String playbookId, String addressId, String idempotencyKey, String tenant) {
        return post("/api/internal/soar/executions")
                .header("Authorization", "Bearer " + TOKEN)
                .header("X-Tenant-ID", tenant)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(playbookId, addressId));
    }

    private static String body(String playbookId, String addressId) {
        return "{\"action_key\":\"START_SOAR_PLAYBOOK\",\"playbook_id\":\""
                + playbookId
                + "\",\"target\":{\"provider\":\"hisiem\",\"resource_type\":\"alert\",\"address_id\":\""
                + addressId
                + "\"}}";
    }
}
