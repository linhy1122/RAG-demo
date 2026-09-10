package com.wuyunbin.rag.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 会话历史消息。
 */
@Schema(description = "会话历史消息")
public record HistoryMessage(

        @Schema(description = "角色：user 或 assistant", example = "user")
        String role,

        @Schema(description = "消息内容", example = "新生报到需要带什么材料？")
        String content
) {
}
