package com.wuyunbin.rag.service;

import com.wuyunbin.rag.config.RagProperties;
import com.wuyunbin.rag.dto.ChatAnswer;
import com.wuyunbin.rag.dto.HistoryMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 多轮问答服务（RAG + 会话记忆）。
 * <p>
 * 主流程：读取历史（一次 get 两个用途：全窗口算字符预算 / 最近 4 条供改写）
 * → 查询改写（隔离调用）→ 检索与阈值过滤 → 上下文预算拼接
 * → 主 ChatClient 调用（Memory Advisor 自动写入）。
 * <p>
 * 兜底语义（三种话术不混用）：
 * <ul>
 *   <li>A「知识库中没有相关内容，请换个问法」：检索结果全部低于阈值；</li>
 *   <li>B「当前会话上下文较长，请清空会话或开启新会话再提问」：检索有结果但 chunkBudget=0；</li>
 *   <li>C「回答生成失败，请稍后重试或换个问法」：主 LLM 调用成功但回复空白（记忆按 gate②=写入型
 *       保留 [U, A_empty]，话术C 仅作为响应）。</li>
 * </ul>
 * A、B 跳过主生成 LLM，手动写入记忆（路径②：user 原始问题 + 兜底话术），保证多轮连贯。
 * <p>
 * gate① 实测：Memory Advisor 在主调用【前】写入 user 消息——LLM 抛异常时记忆残留本轮 user，
 * 需回滚；gate② 实测：空回复时 Advisor 写入 [U, A_empty]（写入型），不移除。
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    /**
     * System Prompt 固定模板（不含【参考资料】部分），其长度即 systemChars（§6.3：
     * 切片长度不计入，避免与 chunkBudget 循环依赖/重复扣减）。
     */
    static final String SYSTEM_TEMPLATE = """
            你是"入学咨询助手"，请仅依据下面提供的【参考资料】回答用户问题。
            - 回答简洁、准确，使用中文。
            - 引用资料时在对应句子末尾标注编号，如 [1]、[2]。
            - 历史对话中出现的 [数字] 编号仅对应当时那一轮的资料，与本轮无关；
              本轮回答的编号请以当前【参考资料】为准。
            - 如果参考资料不足以回答，请直接说明"知识库中没有相关内容"，不要编造。
            """;

    static final String FALLBACK_NO_HIT = "知识库中没有相关内容，请换个问法";
    static final String FALLBACK_BUDGET_EXHAUSTED = "当前会话上下文较长，请清空会话或开启新会话再提问";
    static final String FALLBACK_EMPTY_REPLY = "回答生成失败，请稍后重试或换个问法";

    private static final String SESSION_ID_PATTERN = "^[a-zA-Z0-9_-]{1,64}$";
    private static final int MESSAGE_MAX_CHARS = 2000;
    private static final int TOP_K_MIN = 1;
    private static final int TOP_K_MAX = 20;
    /** 参与查询改写的历史条数（过滤空白 assistant 后取最近 N 条） */
    private static final int REWRITE_HISTORY_MESSAGES = 4;
    /** 改写后 Query 日志打印的最大长度（与 chunk 语义无关，独立常量） */
    private static final int MAX_QUERY_LOG_LENGTH = 200;
    /** 条纹锁数量：ask/history/clear 共用，同会话串行、不同会话并行 */
    private static final int STRIPES = 64;

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final EmbeddingModel embeddingModel;
    private final MilvusRestStore milvusRestStore;
    private final QueryRewriteService queryRewriteService;
    private final RagProperties props;

    private final ReentrantLock[] locks = IntStream.range(0, STRIPES)
            .mapToObj(i -> new ReentrantLock()).toArray(ReentrantLock[]::new);

    public ChatService(@Qualifier("chatClient") ChatClient chatClient,
                       ChatMemory chatMemory,
                       EmbeddingModel embeddingModel,
                       MilvusRestStore milvusRestStore,
                       QueryRewriteService queryRewriteService,
                       RagProperties props) {
        this.chatClient = chatClient;
        this.chatMemory = chatMemory;
        this.embeddingModel = embeddingModel;
        this.milvusRestStore = milvusRestStore;
        this.queryRewriteService = queryRewriteService;
        this.props = props;
    }

    /**
     * 提问（多轮问答主入口）。
     *
     * @param sessionId 会话ID；null/blank 生成新 UUID，非 blank 需通过格式校验
     * @param message   用户问题（非空，≤2000 字符）
     * @param topK      检索条数；null 取配置，最终夹取 [1,20]
     */
    public ChatAnswer ask(String sessionId, String message, Integer topK) {
        validateMessage(message);
        String sid = resolveSessionId(sessionId);
        int resolvedTopK = resolveTopK(topK);

        ReentrantLock lock = lockFor(sid);
        lock.lock();
        try {
            return doAsk(sid, message, resolvedTopK);
        } finally {
            lock.unlock();
        }
    }

    private ChatAnswer doAsk(String sessionId, String message, int topK) {
        // ② 读取历史：一次 get，两个用途互不干扰
        List<Message> allHistory = chatMemory.get(sessionId);
        int historyChars = allHistory.stream()
                .mapToInt(m -> m.getText() == null ? 0 : m.getText().length())
                .sum();
        List<Message> rewriteHistory = recentForRewrite(allHistory);

        // ③ 查询改写（隔离调用，绝不污染正式记忆）
        String rewritten = props.chat().queryRewrite()
                ? queryRewriteService.rewrite(message, rewriteHistory)
                : message;

        // ④ 检索 + 阈值过滤（COSINE，score 越大越相似）；retrievalStart 覆盖整个检索阶段，
        //    未来在 embed 与 search 之间插入逻辑时 total 计算无需改动
        long retrievalStart = System.nanoTime();
        long embedStart = System.nanoTime();
        float[] queryVector = embeddingModel.embed(rewritten);
        long embedCostMs = (System.nanoTime() - embedStart) / 1_000_000L;
        long searchStart = System.nanoTime();
        List<Map<String, Object>> hits = milvusRestStore.search(queryVector, topK);
        long searchCostMs = (System.nanoTime() - searchStart) / 1_000_000L;
        long retrievalTotalMs = (System.nanoTime() - retrievalStart) / 1_000_000L;
        double threshold = props.chat().scoreThreshold();
        List<Map<String, Object>> filtered = hits.stream()
                .filter(h -> score(h) >= threshold)
                .toList();

        // 上下文预算（全窗口历史独立求和，与改写解耦）
        int systemChars = SYSTEM_TEMPLATE.length();
        int chunkBudget = Math.max(0,
                props.chat().contextMaxChars() - historyChars - systemChars - message.length());

        String reply;
        List<ChatAnswer.SourceItem> sources;
        String fallback;

        if (filtered.isEmpty()) {
            // 兜底 A：检索确无相关内容；跳过主生成 LLM，路径②写入 user+话术A
            reply = FALLBACK_NO_HIT;
            sources = List.of();
            fallback = "NO_HIT";
            writeFallbackTurn(sessionId, message, reply);
        } else if (chunkBudget == 0) {
            // 兜底 B：检索到但历史挤占预算；跳过主生成 LLM，路径②写入 user+话术B
            reply = FALLBACK_BUDGET_EXHAUSTED;
            sources = List.of();
            fallback = "BUDGET_EXHAUSTED";
            writeFallbackTurn(sessionId, message, reply);
        } else {
            Refs refs = buildReferences(filtered, chunkBudget);
            String systemPrompt = SYSTEM_TEMPLATE + "\n【参考资料】\n" + refs.text();
            long estTokens = estTokens(List.of(systemPrompt, historyText(allHistory), message));
            try {
                // ⑤ 主 ChatClient 调用（Memory Advisor 自动写入路径①）
                reply = chatClient.prompt()
                        .system(systemPrompt)
                        .user(message)
                        .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                        .call()
                        .content();
            } catch (RuntimeException e) {
                // gate①：Advisor 在调用前已写入 user，异常时回滚本轮 user 消息
                rollbackLastUserTurn(sessionId, message);
                throw e;
            }
            if (reply == null || reply.isBlank()) {
                // 兜底 C：主调用成功但回复空白；gate②=写入型，记忆保留 [U, A_empty]，话术C 仅作响应
                reply = FALLBACK_EMPTY_REPLY;
                sources = List.of();
                fallback = "EMPTY_REPLY";
            } else {
                sources = refs.sources();
                fallback = "NONE";
            }
            logRetrieval(sessionId, rewritten, threshold, hits, sources,
                    embedCostMs, searchCostMs, retrievalTotalMs);
            logTurn(sessionId, message, rewritten, topK, hits, sources.size(),
                    historyChars, systemChars, chunkBudget, estTokens, fallback);
            return new ChatAnswer(sessionId, reply, sources);
        }

        logRetrieval(sessionId, rewritten, threshold, hits, sources,
                embedCostMs, searchCostMs, retrievalTotalMs);
        logTurn(sessionId, message, rewritten, topK, hits, 0,
                historyChars, systemChars, chunkBudget, 0, fallback);
        return new ChatAnswer(sessionId, reply, sources);
    }

    /**
     * 查看会话历史：仅返回当前记忆窗口内消息（含兜底轮次）；
     * 不存在的 sessionId 返回空数组，不报错。
     */
    public List<HistoryMessage> history(String sessionId) {
        validateSessionId(sessionId);
        ReentrantLock lock = lockFor(sessionId);
        lock.lock();
        try {
            return chatMemory.get(sessionId).stream()
                    .map(m -> new HistoryMessage(roleOf(m), m.getText() == null ? "" : m.getText()))
                    .toList();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 清空会话：不存在的 sessionId 幂等处理，不报错。
     */
    public void clear(String sessionId) {
        validateSessionId(sessionId);
        ReentrantLock lock = lockFor(sessionId);
        lock.lock();
        try {
            chatMemory.clear(sessionId);
        } finally {
            lock.unlock();
        }
    }

    // ---------------------------------------------------------------- 私有方法

    /** 兜底轮次手动写入记忆（路径②）：user 原始问题 + 兜底话术，保持对话连贯。 */
    private void writeFallbackTurn(String sessionId, String question, String fallbackText) {
        chatMemory.add(sessionId, new UserMessage(question));
        chatMemory.add(sessionId, new AssistantMessage(fallbackText));
    }

    /**
     * gate① 回滚：Memory Advisor 在主调用前写入 user，LLM 异常时记忆末尾残留本轮 user。
     * 仅当末尾消息确为本轮 user（内容匹配）时移除；在条纹锁内执行，无并发干扰。
     */
    private void rollbackLastUserTurn(String sessionId, String question) {
        try {
            List<Message> all = chatMemory.get(sessionId);
            if (!all.isEmpty()) {
                Message last = all.get(all.size() - 1);
                if (last.getMessageType() == MessageType.USER && question.equals(last.getText())) {
                    List<Message> kept = new ArrayList<>(all.subList(0, all.size() - 1));
                    chatMemory.clear(sessionId);
                    kept.forEach(m -> chatMemory.add(sessionId, m));
                }
            }
        } catch (RuntimeException ex) {
            log.warn("[chat] 回滚本轮 user 消息失败 sessionId={}", sessionId, ex);
        }
    }

    /** 改写用历史：先过滤空白 assistant 消息，再取最近 N 条。 */
    private List<Message> recentForRewrite(List<Message> allHistory) {
        List<Message> nonBlank = allHistory.stream()
                .filter(m -> !(m.getMessageType() == MessageType.ASSISTANT
                        && (m.getText() == null || m.getText().isBlank())))
                .toList();
        int size = nonBlank.size();
        return nonBlank.subList(Math.max(0, size - REWRITE_HISTORY_MESSAGES), size);
    }

    private record Refs(String text, List<ChatAnswer.SourceItem> sources) {
    }

    /**
     * 拼接参考资料：单切片超 chunk-max-chars 截断；累计超 chunkBudget 停止，
     * 未拼入的切片从 sources 同步剔除，保证编号一致。
     */
    private Refs buildReferences(List<Map<String, Object>> filtered, int chunkBudget) {
        int chunkMaxChars = props.chat().chunkMaxChars();
        StringBuilder refs = new StringBuilder();
        List<ChatAnswer.SourceItem> sources = new ArrayList<>();
        int used = 0;
        for (Map<String, Object> hit : filtered) {
            String text = truncate(textOf(hit), chunkMaxChars);
            if (!sources.isEmpty() && used + text.length() > chunkBudget) {
                break;
            }
            int index = sources.size() + 1;
            refs.append('[').append(index).append("] ").append(text).append('\n');
            used += text.length();
            sources.add(new ChatAnswer.SourceItem(index, idOf(hit), score(hit), sourceOf(hit), text));
        }
        return new Refs(refs.toString(), List.copyOf(sources));
    }

    /** COSINE 相似度：Milvus 返回的 distance 即相似度，越大越相似。 */
    private double score(Map<String, Object> hit) {
        Object d = hit.get("distance");
        return d instanceof Number n ? n.doubleValue() : 0.0;
    }

    private String textOf(Map<String, Object> hit) {
        Object t = hit.get("text");
        return t instanceof String s ? s : "";
    }

    private Long idOf(Map<String, Object> hit) {
        Object id = hit.get("id");
        return id instanceof Number n ? n.longValue() : null;
    }

    /** source 归一化：null/blank → "unknown"（raw 与 used 单一口径）。 */
    private String normalizeSource(String src) {
        return (src != null && !src.isBlank()) ? src : "unknown";
    }

    private String sourceOf(Map<String, Object> hit) {
        Object s = hit.get("source");
        return normalizeSource(s instanceof String str ? str : null);
    }

    private String truncate(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxChars ? text : text.substring(0, maxChars);
    }

    private String historyText(List<Message> allHistory) {
        return allHistory.stream()
                .map(m -> m.getText() == null ? "" : m.getText())
                .collect(Collectors.joining("\n"));
    }

    /** 估算 token（中文不低估）：CJK 字符 × 1.0 + 其余字符 ÷ 4。 */
    private long estTokens(List<String> parts) {
        long cjk = 0;
        long others = 0;
        for (String s : parts) {
            if (s == null) {
                continue;
            }
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN
                        || (c >= 0x3000 && c <= 0x303F)
                        || (c >= 0xFF00 && c <= 0xFFEF)) {
                    cjk++;
                } else {
                    others++;
                }
            }
        }
        return cjk + (others + 3) / 4;
    }

    /** 结构化日志（每次提问一行，供联调断言与阈值校准）。 */
    private void logTurn(String sessionId, String raw, String rewritten, int topK,
                         List<Map<String, Object>> hits, int usedCount,
                         int historyChars, int systemChars, int chunkBudget,
                         long estTokens, String fallback) {
        String scores = hits.stream()
                .map(h -> h.get("id") + ":" + score(h))
                .collect(Collectors.joining(",", "[", "]"));
        log.info("[chat] sessionId={}, raw={}, rewritten={}, topK={}, scores={}, used={}, "
                        + "historyChars={}, systemChars={}, chunkBudget={}, estTokens≈{}, fallback={}",
                sessionId, raw, rewritten, topK, scores, usedCount,
                historyChars, systemChars, chunkBudget, estTokens, fallback);
    }

    /**
     * 详细召回日志（受 rag.chat.log-retrieval 控制，INFO 级，独立前缀便于 grep）。
     * 过滤前 topK 每行打 id/score/source/passed 与正文（text 按 chunkMaxChars 截断）；
     * 过滤后实际入 Prompt 的切片再打一遍（used[..]），便于对照「召回 vs 实际使用」。
     * rewritten 过长时按 MAX_QUERY_LOG_LENGTH 截断，避免长查询刷屏。
     */
    private void logRetrieval(String sessionId, String rewritten, double threshold,
                              List<Map<String, Object>> hits, List<ChatAnswer.SourceItem> sources,
                              long embedCostMs, long searchCostMs, long retrievalTotalMs) {
        if (!props.chat().logRetrieval()) {
            return;
        }
        int chunkMaxChars = props.chat().chunkMaxChars();
        int maxRaw = props.chat().logRetrievalMaxRaw();
        log.info("[chat-retrieval] sessionId={}, rewritten={}, threshold={}, rawHits={}, usedRefs={}, "
                        + "embedCostMs={}, searchCostMs={}, retrievalTotalMs={}",
                sessionId, truncate(rewritten, MAX_QUERY_LOG_LENGTH), threshold,
                hits == null ? 0 : hits.size(), sources == null ? 0 : sources.size(),
                embedCostMs, searchCostMs, retrievalTotalMs);

        if (hits != null && !hits.isEmpty()) {
            int shown = 0;
            for (Map<String, Object> h : hits) {
                if (shown >= maxRaw) {
                    break;
                }
                double score = score(h);
                log.info("[chat-retrieval] sessionId={}, raw[{}] id={}, score={}, source={}, passedThreshold={}, text={}",
                        sessionId, shown + 1, idOf(h), score, sourceOf(h), score >= threshold,
                        truncate(textOf(h), chunkMaxChars));
                shown++;
            }
            if (hits.size() > shown) {
                log.info("[chat-retrieval] sessionId={}, raw 省略 {} 条",
                        sessionId, hits.size() - shown);
            }
        }
        if (sources != null) {
            for (ChatAnswer.SourceItem s : sources) {
                // 防御式截断：SourceItem.text 已在 buildReferences 里 truncate 过，
                // 此处仍再 truncate 一次，不依赖其内部实现细节，重复截断无副作用。
                log.info("[chat-retrieval] sessionId={}, used[{}] id={}, score={}, source={}, text={}",
                        sessionId, s.index(), s.id(), s.score(),
                        normalizeSource(s.source()),
                        truncate(s.text(), chunkMaxChars));
            }
        }
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

    private ReentrantLock lockFor(String sessionId) {
        return locks[(sessionId.hashCode() & 0x7fffffff) % STRIPES];
    }

    /** ask 的 sessionId 规则：null/blank 生成新 UUID（不校验）；非 blank 才校验格式。 */
    private String resolveSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return UUID.randomUUID().toString();
        }
        validateSessionId(sessionId);
        return sessionId;
    }

    private void validateSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank() || !sessionId.matches(SESSION_ID_PATTERN)) {
            throw new IllegalArgumentException("sessionId 非法：仅允许字母、数字、下划线、中划线，长度 1~64");
        }
    }

    private void validateMessage(String message) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message 不能为空");
        }
        if (message.length() > MESSAGE_MAX_CHARS) {
            throw new IllegalArgumentException("message 超长（最多 " + MESSAGE_MAX_CHARS + " 字符）");
        }
    }

    private int resolveTopK(Integer topK) {
        int k = topK == null ? props.chat().topK() : topK;
        return Math.max(TOP_K_MIN, Math.min(TOP_K_MAX, k));
    }
}
