package com.wuyunbin.rag.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 多轮问答响应体（JSON 保留 reply 字段名，对旧客户端向后兼容）。
 */
@Schema(description = "多轮问答响应")
public record ChatAnswer(

        @Schema(description = "会话ID；首次提问时由服务端生成，追问时请回传",
                example = "550e8400-e29b-41d4-a716-446655440000")
        String sessionId,

        @Schema(description = "回答内容；兜底轮次为对应兜底话术，sources 为空数组")
        String reply,

        @Schema(description = "引用来源列表，index 与回答中的 [n] 编号一一对应")
        List<SourceItem> sources
) {

    /**
     * 引用来源项（可追溯）。
     *
     * @param index  Prompt 中的编号，从 1 开始连续
     * @param id     Milvus 切片主键
     * @param score  COSINE 相似度，越大越相似
     * @param source 来源文件名（仅文件名，不含路径）
     * @param text   拼入 Prompt 的切片文本（含截断，与 Prompt 一致）
     */
    @Schema(description = "引用来源项")
    public record SourceItem(

            @Schema(description = "Prompt 中的编号，从 1 开始", example = "1")
            int index,

            @Schema(description = "Milvus 切片主键", example = "42")
            Long id,

            @Schema(description = "COSINE 相似度，越大越相似", example = "0.72")
            double score,

            @Schema(description = "来源文件名", example = "jmu新生手册.txt")
            String source,

            @Schema(description = "拼入 Prompt 的切片文本（含截断）", example = "报到时须携带……")
            String text
    ) {
    }
}
