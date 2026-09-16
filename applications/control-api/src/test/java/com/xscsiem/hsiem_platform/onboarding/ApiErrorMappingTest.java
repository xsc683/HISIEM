package com.xscsiem.hsiem_platform.onboarding;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xscsiem.hsiem_platform.soar.SoarDictionary;
import com.xscsiem.hsiem_platform.soar.SoarService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 客户端错误必须是 4xx,不能落到 catch-all 的 500。
 *
 * <p>回归:未知路径、缺少必填查询参数、HTTP 方法不支持此前都返回 500 INTERNAL_ERROR, 把调用方的错误报成服务端故障。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApiErrorMappingTest {

    @Autowired private MockMvc mvc;

    @MockitoBean private SoarService soarService;

    @MockitoBean private SoarDictionary soarDictionary;

    @Test
    void missingRequiredRequestParameter_isBadRequest() throws Exception {
        mvc.perform(get("/api/soar/action-dictionary").with(user("admin").roles("ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void unknownRoute_isNotFound() throws Exception {
        mvc.perform(get("/api/no-such-endpoint-e2e").with(user("admin").roles("ADMIN")))
                .andExpect(status().isNotFound());
    }

    @Test
    void unsupportedMethod_isMethodNotAllowed() throws Exception {
        mvc.perform(delete("/api/alerts").with(user("admin").roles("ADMIN")))
                .andExpect(status().isMethodNotAllowed());
    }
}
