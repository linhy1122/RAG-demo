package com.wuyunbin.rag.config;

import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 会话记忆配置：内存 MessageWindowChatMemory（窗口大小走配置）+ Memory Advisor。
 * <p>
 * gate③ 已核实 ChatMemory API 面仅 add/get/clear；窗口满时按最早消息淘汰。
 */
@Configuration
public class ChatMemoryConfig {

    @Bean
    public ChatMemory chatMemory(RagProperties props) {
        return MessageWindowChatMemory.builder()
                .maxMessages(props.chat().memoryMaxMessages())
                .build();
    }

    @Bean
    public MessageChatMemoryAdvisor messageChatMemoryAdvisor(ChatMemory chatMemory) {
        return MessageChatMemoryAdvisor.builder(chatMemory).build();
    }
}
