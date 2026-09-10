package com.wuyunbin.rag.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Duration;

/**
 * 双 ChatClient 配置：
 * <ul>
 *   <li>{@code chatClient}(@Primary)：主生成，显式挂 Memory Advisor（Advisor 不会自动生效），
 *       maxTokens 走配置；所有注入点用 @Qualifier("chatClient") 引用。</li>
 *   <li>{@code rewriteChatClient}：查询改写专用，手动构建（避开 Builder 状态共享污染）、
 *       无 Advisor、temperature=0.1，与主会话记忆完全隔离。</li>
 *   <li>{@code OpenAiHttpClientBuilderCustomizer}：LM Studio 响应超时。
 *       gate④ 已验证 OpenAI 自动配置会拾取该 Bean（RestClientCustomizer 无效，故不用）；
 *       MilvusRestStore 自建 RestClient 实例不受影响。</li>
 * </ul>
 */
@Configuration
public class ChatConfig {

    @Bean
    @Primary
    public ChatClient chatClient(ChatClient.Builder builder,
                                 MessageChatMemoryAdvisor memoryAdvisor,
                                 RagProperties props) {
        // Spring AI 2.0 的 defaultOptions 接收 ChatOptions.Builder（非构建后的实例）
        OpenAiChatOptions.Builder opts = OpenAiChatOptions.builder();
        opts.maxTokens(props.chat().maxTokens());
        return builder
                .defaultAdvisors(memoryAdvisor)
                .defaultOptions(opts)
                .build();
    }

    /**
     * 改写专用 ChatClient：按 OpenAiChatModel 具体类型注入（不用 @Qualifier）。
     * model 未配置时留空，回退 ChatModel 默认 options，启动不失败。
     */
    @Bean
    public ChatClient rewriteChatClient(
            OpenAiChatModel chatModel,
            @Value("${spring.ai.openai.chat.options.model:}") String model) {
        OpenAiChatOptions.Builder opts = OpenAiChatOptions.builder();
        opts.temperature(0.1);
        if (model != null && !model.isBlank()) {
            opts.model(model);
        }
        return ChatClient.builder(chatModel)
                .defaultOptions(opts)
                .build();
    }

    /**
     * 超时定制：timeout 为整体请求超时（含连接与读取），
     * 对主生成与改写调用统一生效；不额外收紧连接超时（模型首次加载握手可能偏慢）。
     */
    @Bean
    public OpenAiHttpClientBuilderCustomizer lmStudioTimeoutCustomizer(
            @Value("${rag.chat.timeout-seconds:60}") int timeoutSeconds) {
        return builder -> builder.timeout(Duration.ofSeconds(timeoutSeconds));
    }
}
