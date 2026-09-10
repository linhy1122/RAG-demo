package com.wuyunbin.rag.controller;

import com.wuyunbin.rag.dto.ChatAnswer;
import com.wuyunbin.rag.dto.ChatRequest;
import com.wuyunbin.rag.dto.HistoryMessage;
import com.wuyunbin.rag.service.ChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 多轮问答接口：提问 / 查看会话历史 / 清空会话。
 */
@RestController
@RequestMapping("/api/chat")
@Tag(name = "聊天接口", description = "多轮问答相关接口")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping
    @Operation(summary = "多轮问答提问",
            description = "基于知识库检索回答问题并记住会话上下文；首次不传 sessionId，追问时回传")
    public ChatAnswer ask(@RequestBody ChatRequest request) {
        return chatService.ask(request.sessionId(), request.message(), request.topK());
    }

    @GetMapping("/{sessionId}/history")
    @Operation(summary = "查看会话历史",
            description = "返回当前记忆窗口内的消息序列（含兜底轮次）；不存在的 sessionId 返回空数组")
    public List<HistoryMessage> history(@PathVariable String sessionId) {
        return chatService.history(sessionId);
    }

    @DeleteMapping("/{sessionId}")
    @Operation(summary = "清空会话",
            description = "删除该会话的记忆；不存在的 sessionId 幂等返回 200")
    public Map<String, Object> clear(@PathVariable String sessionId) {
        chatService.clear(sessionId);
        return Map.of("cleared", true, "sessionId", sessionId);
    }

    /** 参数校验失败（sessionId/message 非法）统一转 400。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
    }

    /** 请求体不可读 / Content-Type 错误转 400（避免落入通用 500 处理）。 */
    @ExceptionHandler({HttpMessageNotReadableException.class, HttpMediaTypeNotSupportedException.class})
    public ResponseEntity<Map<String, String>> badContent(Exception e) {
        return ResponseEntity.badRequest().body(Map.of("error", "请求格式错误：需合法 JSON 且 Content-Type 为 application/json"));
    }

    /** 业务异常（LLM/Embedding/Milvus 等）转 5xx；响应体不含内部堆栈。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> serverError(Exception e) {
        log.error("[chat] 请求处理失败", e);
        return ResponseEntity.internalServerError().body(Map.of("error", "服务内部错误，请稍后重试"));
    }
}
