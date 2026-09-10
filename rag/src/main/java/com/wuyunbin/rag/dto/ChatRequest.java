package com.wuyunbin.rag.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 多轮问答请求体。
 */
@Schema(description = "多轮问答请求")
public record ChatRequest(

        @Schema(description = "会话ID；首次不传或传空白由服务端生成新会话，追问时回传上次响应中的 sessionId",
                example = "550e8400-e29b-41d4-a716-446655440000")
        String sessionId,

        @Schema(description = "用户输入的问题内容（非空，最多 2000 字符）",
                example = "新生报到需要带什么材料？", requiredMode = Schema.RequiredMode.REQUIRED)
        String message,

        @Schema(description = "检索条数；不传取服务端配置默认值，最终夹取 [1,20]", example = "5")
        Integer topK
) {
}
