package com.github.h3yow.versionedapiidempotencygatewayjava.tests.integ;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 两个调用入口（REST /api/orders 与通用 /gateway/invoke）共用同一门面，
 * 版本协商、幂等、权限、错误契约必须一致。
 */
@SpringBootTest
@AutoConfigureMockMvc
class EntryPointConsistencyTest {

    @Autowired
    private MockMvc mockMvc;

    private static final String BODY = "{\"item\":\"pen\",\"quantity\":2}";

    @Test
    @DisplayName("两个入口：未授权均返回相同的 404 RESOURCE_NOT_FOUND 契约")
    void unauthorizedSameAcrossEntries() throws Exception {
        for (String path : new String[]{"/api/orders", "/gateway/invoke"}) {
            var req = post(path)
                    .header("X-Api-Version", "2.0")
                    .header("X-Client-Id", "client-unknown")
                    .header("Idempotency-Key", "k-entry-" + path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(BODY);
            if (path.equals("/gateway/invoke")) {
                req.header("X-Capability", "ORDER_CREATE");
            }
            mockMvc.perform(req)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                    .andExpect(jsonPath("$.message").value("resource not found"));
        }
    }

    @Test
    @DisplayName("两个入口：跨主版本不兼容均返回 VERSION_INCOMPATIBLE")
    void incompatibleVersionSameAcrossEntries() throws Exception {
        for (String path : new String[]{"/api/orders", "/gateway/invoke"}) {
            var req = post(path)
                    .header("X-Api-Version", "3.0")
                    .header("X-Client-Id", "client-a")
                    .header("Idempotency-Key", "k-entry-ver-" + path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(BODY);
            if (path.equals("/gateway/invoke")) {
                req.header("X-Capability", "ORDER_CREATE");
            }
            mockMvc.perform(req)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VERSION_INCOMPATIBLE"));
        }
    }

    @Test
    @DisplayName("通用入口成功调用，选择 v2 处理路径并遵循幂等回放")
    void genericEntrySucceedsAndReplays() throws Exception {
        String key = "k-generic-entry";
        for (int i = 0; i < 2; i++) {
            var result = mockMvc.perform(post("/gateway/invoke")
                            .header("X-Api-Version", "2.0")
                            .header("X-Client-Id", "client-a")
                            .header("Idempotency-Key", key)
                            .header("X-Capability", "ORDER_CREATE")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(BODY))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.resolvedVersion").value("2.0"))
                    .andExpect(jsonPath("$.data.handlerVersion").value("2"));
            if (i == 1) {
                result.andExpect(jsonPath("$.idempotency.status").value("REPLAY_SUCCEEDED"));
            }
        }
    }
}
