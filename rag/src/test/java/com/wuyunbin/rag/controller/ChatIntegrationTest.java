package com.wuyunbin.rag.controller;

import com.wuyunbin.rag.service.MilvusRestStore;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 集成测试（切片 A，计划 §8.2）：@SpringBootTest + MockMvc + @MockitoBean。
 * <p>
 * 外部协作 Mock：OpenAiChatModel（主生成与改写共用，含 ChatClient.Builder 绑定链）、
 * EmbeddingModel、MilvusRestStore——不打到真实 LM Studio / Milvus。
 * 会话记忆使用真实内存 ChatMemory（ChatMemoryConfig Bean）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ChatIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OpenAiChatModel openAiChatModel;

    @MockitoBean
    private EmbeddingModel embeddingModel;

    @MockitoBean
    private MilvusRestStore milvusRestStore;

    @BeforeEach
    void setUp() {
        // OpenAiChatModel 被 @MockitoBean 覆盖后，ChatClient.Builder（注入 ChatModel）绑定该 Mock
        // 注：getOptions() 协变返回 OpenAiChatOptions
        when(openAiChatModel.getOptions()).thenReturn(OpenAiChatOptions.builder().build());
        when(embeddingModel.embed(anyString())).thenReturn(new float[]{1f, 0f});
        when(milvusRestStore.search(any(float[].class), anyInt())).thenReturn(List.of(
                hit(42L, 0.72, "报到时须携带身份证和录取通知书", "jmu新生手册.txt"),
                hit(87L, 0.65, "报到时间为9月1日至2日", "jmu新生手册.txt")));
    }

    // ---------------------------------------------------------------- 成功类

    @Test
    @DisplayName("I1 首轮（不传 sessionId）：200；sessionId 合法 UUID；sources 每项含五字段")
    void I1_firstTurnReturnsUuidAndSources() throws Exception {
        when(openAiChatModel.call(any(Prompt.class))).thenReturn(response("根据入学手册，需要携带身份证[1][2]"));

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"新生报到需要带什么材料？\"}".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(Matchers.matchesPattern("^[0-9a-fA-F-]{36}$")))
                .andExpect(jsonPath("$.reply").value("根据入学手册，需要携带身份证[1][2]"))
                .andExpect(jsonPath("$.sources.length()").value(2))
                .andExpect(jsonPath("$.sources[0].index").value(1))
                .andExpect(jsonPath("$.sources[0].id").value(42))
                .andExpect(jsonPath("$.sources[0].score").value(0.72))
                .andExpect(jsonPath("$.sources[0].source").value("jmu新生手册.txt"))
                .andExpect(jsonPath("$.sources[0].text").value("报到时须携带身份证和录取通知书"))
                .andExpect(jsonPath("$.sources[1].index").value(2))
                .andExpect(jsonPath("$.sources[1].id").value(87));
    }

    @Test
    @DisplayName("I2 追问（带 sessionId）：200；sessionId 不变；主 LLM 收到的 prompt 含历史消息")
    void I2_followUpKeepsSessionAndInjectsHistory() throws Exception {
        when(openAiChatModel.call(any(Prompt.class)))
                .thenReturn(response("首轮回答"))
                .thenReturn(response("改写后的检索查询"))
                .thenReturn(response("根据历史，报到时间是9月1日[1]"));

        MvcResult first = mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"新生报到需要带什么材料？\"}".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk())
                .andReturn();
        String sessionId = extractSessionId(first.getResponse().getContentAsString());

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"sessionId\":\"" + sessionId + "\",\"message\":\"那具体时间是几号？\"}")
                                .getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(sessionId))
                .andExpect(jsonPath("$.reply").value("根据历史，报到时间是9月1日[1]"));

        // 验证 Advisor 生效：主生成 prompt（含【参考资料】的那个）注入了第一轮历史
        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(openAiChatModel, atLeastOnce()).call(captor.capture());
        Prompt mainPrompt = captor.getAllValues().stream()
                .filter(p -> p.getInstructions().stream()
                        .anyMatch(m -> m.getText() != null && m.getText().contains("【参考资料】")))
                .reduce((a, b) -> b)
                .orElseThrow();
        assertThat(mainPrompt.getInstructions())
                .anyMatch(m -> m.getText() != null && m.getText().contains("新生报到需要带什么材料？"));
    }

    @Test
    @DisplayName("I3 查看历史：200；返回窗口内 role/content 序列，含兜底轮次")
    void I3_historyIncludesFallbackTurns() throws Exception {
        // 检索结果全被阈值过滤 → 兜底A 轮次
        when(milvusRestStore.search(any(float[].class), anyInt()))
                .thenReturn(List.of(hit(1L, 0.10, "低分内容", "a.txt")));

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"i3\",\"message\":\"知识库外问题\"}".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("知识库中没有相关内容，请换个问法"));

        mockMvc.perform(get("/api/chat/{sessionId}/history", "i3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].role").value("user"))
                .andExpect(jsonPath("$[0].content").value("知识库外问题"))
                .andExpect(jsonPath("$[1].role").value("assistant"))
                .andExpect(jsonPath("$[1].content").value("知识库中没有相关内容，请换个问法"));
    }

    @Test
    @DisplayName("I4 清空会话：200；之后 history 返回空数组；再 ask 正常（视为新会话语境）")
    void I4_clearThenHistoryEmpty() throws Exception {
        when(openAiChatModel.call(any(Prompt.class))).thenReturn(response("首轮回答"));
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"i4\",\"message\":\"第一轮问题\"}".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/chat/{sessionId}", "i4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cleared").value(true));

        mockMvc.perform(get("/api/chat/{sessionId}/history", "i4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        // 清空后再提问正常返回（重新走完整流程）
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"i4\",\"message\":\"第二轮问题\"}".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").isNotEmpty());
    }

    // ---------------------------------------------------------------- 失败类

    @Test
    @DisplayName("I5 sessionId 含非法字符：400，错误信息明确")
    void I5_invalidSessionId() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"abc$123\",\"message\":\"问题\"}".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(Matchers.containsString("sessionId")));
    }

    @Test
    @DisplayName("I6 message 为 3000 字符：400")
    void I6_messageTooLong() throws Exception {
        String longMessage = "长".repeat(3000);
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"message\":\"" + longMessage + "\"}").getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("I7 message 为空字符串 / 字段缺失：400")
    void I7_emptyOrMissingMessage() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"\"}".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"abc\"}".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("I8 不存在的 sessionId：history 返回空数组、clear 幂等 200（不 404/500）")
    void I8_nonExistentSessionId() throws Exception {
        mockMvc.perform(get("/api/chat/{sessionId}/history", "nonexistent-session"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(delete("/api/chat/{sessionId}", "nonexistent-session"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cleared").value(true));
    }

    @Test
    @DisplayName("I9 EmbeddingModel Mock 抛异常：5xx；响应体不含内部堆栈")
    void I9_embeddingThrowsReturns5xx() throws Exception {
        when(embeddingModel.embed(anyString())).thenThrow(new RuntimeException("embedding down"));

        MvcResult result = mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"问题\"}".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("服务内部错误，请稍后重试"))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("embedding down").doesNotContain("RuntimeException");
    }

    @Test
    @DisplayName("I10 检索 hits 全被阈值过滤：200；reply=话术A、sources=[]（兜底而非报错）")
    void I10_allHitsFilteredFallback() throws Exception {
        when(milvusRestStore.search(any(float[].class), anyInt()))
                .thenReturn(List.of(hit(1L, 0.10, "无关低分内容", "a.txt")));

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"知识库外的问题\"}".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("知识库中没有相关内容，请换个问法"))
                .andExpect(jsonPath("$.sources.length()").value(0));
    }

    @Test
    @DisplayName("I11 非法 JSON / Content-Type 错误：400")
    void I11_illegalJsonAndWrongContentType() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{bad json".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("plain text".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- 辅助

    private String extractSessionId(String body) {
        Matcher m = Pattern.compile("\"sessionId\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    private static ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private static Map<String, Object> hit(Long id, double distance, String text, String source) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("distance", distance);
        m.put("text", text);
        m.put("source", source);
        return m;
    }
}
