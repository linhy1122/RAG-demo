package com.wuyunbin.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Rag 相关配置项，对应 application.properties 中 rag.* 前缀。
 * 通过启动类上的 @ConfigurationPropertiesScan 注册（构造器绑定）。
 *
 * @param milvus Milvus 连接与知识库配置
 * @param ingest 文档导入配置
 * @param chat   多轮问答配置
 */
@ConfigurationProperties(prefix = "rag")
public record RagProperties(Milvus milvus, Ingest ingest, Chat chat) {

    /**
     * 紧凑构造器：rag.chat.* 全未配置时填充默认值，保证零配置可启动（不 NPE）。
     * 部分配置时由各字段 @DefaultValue 兜底。
     */
    public RagProperties {
        if (chat == null) {
            chat = new Chat(10, 0.60, 20, true, 600, 4000, 1024);
        }
    }

    public record Milvus(String baseUrl, String collection, int embeddingDimension) {
    }

    public record Ingest(int batchSize, int defaultTopK) {
    }

    /**
     * 多轮问答配置。
     *
     * @param topK              每次提问默认检索条数（请求参数可覆盖，最终夹取 [1,20]）
     * @param scoreThreshold    COSINE 相似度阈值，越大越相似；score >= 阈值才保留
     * @param memoryMaxMessages 会话记忆窗口（保留最近 N 条消息，含 user+assistant 与兜底轮次）
     * @param queryRewrite      是否启用多轮查询改写
     * @param chunkMaxChars     单切片拼入 Prompt 的最大字符数
     * @param contextMaxChars   检索上下文总字符预算（被历史/系统模板/用户问题动态扣减）
     * @param maxTokens         单次回答 maxTokens 上限
     */
    public record Chat(
            @DefaultValue("10") int topK,
            @DefaultValue("0.60") double scoreThreshold,
            @DefaultValue("20") int memoryMaxMessages,
            @DefaultValue("true") boolean queryRewrite,
            @DefaultValue("600") int chunkMaxChars,
            @DefaultValue("4000") int contextMaxChars,
            @DefaultValue("1024") int maxTokens) {
    }
}