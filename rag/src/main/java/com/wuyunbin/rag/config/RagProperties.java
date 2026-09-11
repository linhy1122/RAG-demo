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
public record RagProperties(Milvus milvus, Ingest ingest, Chat chat, Clean clean, Test test) {

    /**
     * 紧凑构造器：rag.* 下未配置的组件填充默认值，保证零配置启动（不 NPE）。
     * 部分配置时由各字段 @DefaultValue 兜底。
     */
    public RagProperties {
        if (chat == null) {
            chat = new Chat(10, 0.60, 20, true, 600, 4000, 1024, true, 10);
        }
        if (clean == null) {
            clean = new Clean(true, 1500);
        }
        if (test == null) {
            test = new Test(false);
        }
    }

    public record Milvus(String baseUrl, String collection, int embeddingDimension) {
    }

    public record Ingest(int batchSize, int defaultTopK,
                         @DefaultValue("true") boolean clearBeforeIngest) {
    }

    /**
     * 入库清洗配置。
     *
     * @param enabled       是否启用清洗（曲调/目录过滤 + 表格原子保留）
     * @param tableMaxBytes 单一表块最大字节数（UTF-8），超过则按行二次切分，
     *                      每个子块仍保留表头+对齐分隔行；与 Milvus text 字段
     *                      max_length=2048 字节对齐，默认 1500 留余量。
     */
    public record Clean(@DefaultValue("true") boolean enabled,
                        @DefaultValue("1500") int tableMaxBytes) {
    }

    /**
     * 联调测试专用配置（rag.test.*），与生产业务配置隔离，上线前必须为 false。
     *
     * @param simulateEmbedFailure 模拟向量化失败，用于验证"embed 先于 Drop，失败不清空旧数据"
     */
    public record Test(@DefaultValue("false") boolean simulateEmbedFailure) {
    }

    /**
     * 多轮问答配置。
     *
     * @param topK              每次提问默认检索条数（请求参数可覆盖，最终夹取 [1,20]）
     * @param scoreThreshold    COSINE 相似度阈值，越大越相似；score >= 阈值才保留
     * @param memoryMaxMessages 会话记忆窗口（保留最近 N 条消息，含 user+assistant 与兜底轮次）
     * @param queryRewrite      是否启用多轮查询改写
     * @param chunkMaxChars     单切片拼入 Prompt 的最大字符数
     * @param contextMaxChars    检索上下文总字符预算（被历史/系统模板/用户问题动态扣减）
     * @param maxTokens          单次回答 maxTokens 上限
     * @param logRetrieval       是否在 INFO 级打印每次提问的召回明细（id/score/source/text 与耗时）；
     *                           开发默认开启便于联调，生产部署建议覆盖为 false
     * @param logRetrievalMaxRaw 每次提问 raw（过滤前）日志最多打印条数，超限仅打省略行
     */
    public record Chat(
            @DefaultValue("10") int topK,
            @DefaultValue("0.60") double scoreThreshold,
            @DefaultValue("20") int memoryMaxMessages,
            @DefaultValue("true") boolean queryRewrite,
            @DefaultValue("600") int chunkMaxChars,
            @DefaultValue("4000") int contextMaxChars,
            @DefaultValue("1024") int maxTokens,
            @DefaultValue("true") boolean logRetrieval,
            @DefaultValue("10") int logRetrievalMaxRaw) {
    }
}