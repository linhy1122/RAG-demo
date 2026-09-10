package com.wuyunbin.rag.gate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * §9 第 0 步 gate 验证（硬性 gate，结论写入实施记录）：
 * gate① Advisor 异常时的写入行为；
 * gate② Advisor 空回复时的写入行为（写入 [U, A_empty] 还是跳过）；
 * gate③ ChatMemory API 面（add/get/clear）。
 * 说明：Advisor 行为与具体模型无关，用 Mock ChatModel 即可观测真实 Advisor 实现。
 */
class GateAdvisorBehaviorTest {

    private ChatMemory memory;
    private ChatClient client;

    @BeforeEach
    void setUp() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.getOptions()).thenReturn(org.springframework.ai.chat.prompt.ChatOptions.builder().build());
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("正常回复")))));
        memory = MessageWindowChatMemory.builder().maxMessages(20).build();
        client = ChatClient.builder(chatModel)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(memory).build())
                .build();
    }

    /** gate③：ChatMemory API 面核实（仅 add/get/clear，窗口内存实现可用）。 */
    @Test
    void gate3_chatMemoryApiSurface() {
        memory.add("g3", new UserMessage("hello"));
        assertThat(memory.get("g3")).hasSize(1);
        memory.clear("g3");
        assertThat(memory.get("g3")).isEmpty();
    }

    /**
     * gate①：主 LLM 抛异常时，Advisor 是否已把 user 消息写入记忆？
     * 结论打印到 stdout，用于决定 ChatService 是否需要回滚逻辑（预期：无需回滚）。
     */
    @Test
    void gate1_advisorBehaviorOnException() {
        ChatModel throwing = mock(ChatModel.class);
        when(throwing.getOptions()).thenReturn(org.springframework.ai.chat.prompt.ChatOptions.builder().build());
        when(throwing.call(any(Prompt.class))).thenThrow(new RuntimeException("boom"));
        ChatClient throwingClient = ChatClient.builder(throwing)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(memory).build())
                .build();

        assertThatThrownBy(() -> throwingClient.prompt().user("异常轮问题")
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, "g1"))
                .call().content())
                .isInstanceOf(RuntimeException.class)
                .hasMessage("boom");

        List<Message> after = memory.get("g1");
        System.out.println("[GATE1] 异常后记忆内容 = " + after);
        after.forEach(m -> System.out.println("[GATE1]   " + m.getMessageType() + ": " + m.getText()));
    }

    /**
     * gate②：主 LLM 成功返回空白内容时，Advisor 写入 [U, A_empty] 还是跳过？
     * 结论决定：话术C 场景下记忆为 [U, A_empty]（写入型，话术C 不写）
     * 还是孤立 U（跳过型，需路径③手动写入 user+话术C）。
     */
    @Test
    void gate2_advisorBehaviorOnEmptyReply() {
        ChatModel empty = mock(ChatModel.class);
        when(empty.getOptions()).thenReturn(org.springframework.ai.chat.prompt.ChatOptions.builder().build());
        when(empty.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("")))));
        ChatClient emptyClient = ChatClient.builder(empty)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(memory).build())
                .build();

        String reply = emptyClient.prompt().user("空回复轮问题")
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, "g2"))
                .call().content();

        System.out.println("[GATE2] 客户端拿到 reply = [" + reply + "]");
        List<Message> after = memory.get("g2");
        System.out.println("[GATE2] 空回复后记忆内容 = " + after);
        after.forEach(m -> System.out.println("[GATE2]   " + m.getMessageType() + ": " + m.getText()));
    }

    /** 附加：正常回复的记忆写入顺序（user 原始问题在前、assistant 回复在后）。 */
    @Test
    void normalReplyWritesUserThenAssistant() {
        client.prompt().user("第一轮问题")
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, "g4"))
                .call().content();

        List<Message> after = memory.get("g4");
        System.out.println("[GATE-N] 正常轮记忆 = " + after);
        assertThat(after).hasSize(2);
        assertThat(after.get(0).getMessageType()).isEqualTo(org.springframework.ai.chat.messages.MessageType.USER);
        assertThat(after.get(0).getText()).isEqualTo("第一轮问题");
        assertThat(after.get(1).getMessageType()).isEqualTo(org.springframework.ai.chat.messages.MessageType.ASSISTANT);
        assertThat(after.get(1).getText()).isEqualTo("正常回复");
    }
}
