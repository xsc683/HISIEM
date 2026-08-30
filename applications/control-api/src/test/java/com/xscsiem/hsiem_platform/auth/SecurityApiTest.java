package com.xscsiem.hsiem_platform.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

/** 阶段 4.2:Spring Security 认证、持久会话入口和统一错误结构。 */
@SpringBootTest
@AutoConfigureMockMvc
class SecurityApiTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void protectedApiWithoutTokenReturnsUnified401() throws Exception {
        mvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void loginReturnsBearerTokenAndMeAcceptsIt() throws Exception {
        String response = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString())
                .andReturn().getResponse().getContentAsString();
        String token = response.replaceAll(".*\\\"token\\\":\\\"([^\\\"]+)\\\".*", "$1");
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("admin"))
                .andExpect(jsonPath("$.role").value("admin"));
    }

    @Test
    void actuatorHealthIsPublicButMetricsAreAdminOnly() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
    }

    @Test
    void soarPlaybooksAreReadableButMutationIsForbiddenForAudit() throws Exception {
        mvc.perform(get("/api/soar/playbooks").with(user("auditor").roles("AUDIT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
        mvc.perform(post("/api/soar/playbooks").with(user("auditor").roles("AUDIT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"entryType\":\"alert\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void opsCanTestUnsavedCustomParserTemplate() throws Exception {
        mvc.perform(post("/api/parser-templates/test-custom").with(user("operator").roles("OPS"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sample": "source=192.0.2.10",
                                  "template": {
                                    "patterns": ["source=%{IP:source.ip}"],
                                    "ecs": {"event.category": "network"}
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.fields['source.ip']").value("192.0.2.10"))
                .andExpect(jsonPath("$.fields['event.category']").value("network"));
    }
}
