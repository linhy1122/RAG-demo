package com.wuyunbin.rag.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 多轮查询改写服务：把"追问"结合最近几轮历史改写为独立、自包含的检索查询。
 * <p>
 * 隔离性约定：
 * <ul>
 *   <li>使用独立的 {@code rewriteChatClient}（手动构建、无 Memory Advisor），绝不读写正式会话记忆；</li>
 *   <li>try-catch 全包裹：改写失败/为空/超长一律回退原始问题，不阻塞主流程。</li>
 * </ul>
 */
@Service
public class QueryRewriteService {

    private static final Logger log = LoggerFactory.getLogger(QueryRewriteService.class);

    private static final String REWRITE_INSTRUCTION =
            "根据对话历史，把用户最新问题改写成一个独立、自包含的检索查询。"
                    + "只输出改写后的查询本身，不要任何解释、引号或多余文字。";

    /** 改写输出超过该长度视为异常结果，回退原始问题 */
    private static final int MAX_REWRITE_OUTPUT_CHARS = 100;

    private final ChatClient rewriteChatClient;

    public QueryRewriteService(@Qualifier("rewriteChatClient") ChatClient rewriteChatClient) {
        this.rewriteChatClient = rewriteChatClient;
    }

    /**
     * 改写查询。
     *
     * @param question       用户原始问题
     * @param recentHistory  最近几条历史消息（已过滤空白 assistant，可为空）
     * @return 改写后的查询；无历史或任何失败场景返回原始问题
     */
    public String rewrite(String question, List<Message> recentHistory) {
        if (recentHistory == null || recentHistory.isEmpty()) {
            // 无历史：直接用原问题，跳过 LLM 调用
            return question;
        }
        try {
            String rewritten = rewriteChatClient.prompt()
                    .user(buildRewriteInput(question, recentHistory))
                    .call()
                    .content();
            if (rewritten == null || rewritten.isBlank()) {
                log.warn("[rewrite] 改写输出为空，回退原始问题");
                return question;
            }
            rewritten = rewritten.trim();
            if (rewritten.length() > MAX_REWRITE_OUTPUT_CHARS) {
                log.warn("[rewrite] 改写输出超长({}字符)，回退原始问题", rewritten.length());
                return question;
            }
            return rewritten;
        } catch (Exception e) {
            log.warn("[rewrite] 查询改写失败，回退原始问题: {}", e.getMessage());
            return question;
        }
    }

    private String buildRewriteInput(String question, List<Message> recentHistory) {
        StringBuilder sb = new StringBuilder();
        sb.append(REWRITE_INSTRUCTION).append("\n\n对话历史:\n");
        for (Message m : recentHistory) {
            sb.append(roleOf(m)).append(": ").append(m.getText()).append('\n');
        }
        sb.append("\n用户最新问题: ").append(question);
        return sb.toString();
    }

    private String roleOf(Message m) {
        MessageType type = m.getMessageType();
        if (type == MessageType.USER) {
            return "user";
        }
        if (type == MessageType.ASSISTANT) {
            return "assistant";
        }
        return type.getValue();
    }
}
