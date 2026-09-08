package com.wuyunbin.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Rag 相关配置项，对应 application.properties 中 rag.* 前缀。
 * 通过启动类上的 @ConfigurationPropertiesScan 注册（构造器绑定）。
 *
 * @param milvus Milvus 连接与知识库配置
 * @param ingest 文档导入配置
 */
@ConfigurationProperties(prefix = "rag")
public record RagProperties(Milvus milvus, Ingest ingest) {

    public record Milvus(String baseUrl, String collection, int embeddingDimension) {
    }

    public record Ingest(int batchSize, int defaultTopK) {
    }
}