package com.wuyunbin.rag.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.wuyunbin.rag.config.RagProperties;
import com.wuyunbin.rag.dto.ChatAnswer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 多轮问答单元测试（计划 §8.1 U1~U15，外部协作全部 Mock，无外部服务依赖）。
 * <p>
 * 与生产装配一致：围绕 Mock ChatModel 构建真实 ChatClient，
 * 显式挂载 MessageChatMemoryAdvisor（真实内存 ChatMemory）——否则 U1 的"记忆新增"断言无从成立。
 */
class ChatServiceTest {

    private ChatModel mainModel;
    private ChatModel rewriteModel;
    private ChatMemory memory;
    private EmbeddingModel embeddingModel;
    private MilvusRestStore milvusRestStore;
    private RagProperties props;
    private ChatService service;

    @BeforeEach
    void setUp() {
        mainModel = mock(ChatModel.class);
        when(mainModel.getOptions()).thenReturn(ChatOptions.builder().build());
        when(mainModel.call(any(Prompt.class))).thenReturn(response("回答"));

        rewriteModel = mock(ChatModel.class);
        when(rewriteModel.getOptions()).thenReturn(ChatOptions.builder().build());
        when(rewriteModel.call(any(Prompt.class))).thenReturn(response("改写查询"));

        memory = MessageWindowChatMemory.builder().maxMessages(20).build();

        embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embed(anyString())).thenReturn(new float[]{1f, 0f});

        milvusRestStore = mock(MilvusRestStore.class);
        when(milvusRestStore.search(any(float[].class), anyInt())).thenReturn(List.of(
                hit(1L, 0.90, "文本A相关内容", "a.txt"),
                hit(2L, 0.80, "文本B相关内容", "b.txt")));

        props = new RagProperties(null, null, null);
        service = newService(props);
    }

    private ChatService newService(RagProperties customProps) {
        // 与生产 ChatConfig 装配一致：显式挂 MessageChatMemoryAdvisor
        ChatClient mainClient = ChatClient.builder(mainModel)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(memory).build())
                .build();
        QueryRewriteService rewriteService =
                new QueryRewriteService(ChatClient.builder(rewriteModel).build());
        return new ChatService(mainClient, memory, embeddingModel, milvusRestStore,
                rewriteService, customProps);
    }

    // ---------------------------------------------------------------- 成功类

    @Test
    @DisplayName("U1 ask 主流程 happy path：reply+sources 正确，记忆新增 user(原始问题)+assistant")
    void U1_askHappyPath() {
        when(mainModel.call(any(Prompt.class))).thenReturn(response("根据手册，需要携带材料[1][2]"));

        ChatAnswer answer = service.ask("u1", "新生报到需要带什么材料？", null);

        assertThat(answer.sessionId()).isEqualTo("u1");
        assertThat(answer.reply()).isEqualTo("根据手册，需要携带材料[1][2]");
        assertThat(answer.sources()).hasSize(2);
        assertThat(answer.sources()).extracting(ChatAnswer.SourceItem::index).containsExactly(1, 2);
        assertThat(answer.sources().get(0).id()).isEqualTo(1L);
        assertThat(answer.sources().get(0).score()).isEqualTo(0.90);
        assertThat(answer.sources().get(0).source()).isEqualTo("a.txt");
        assertThat(answer.sources().get(0).text()).isEqualTo("文本A相关内容");
        assertThat(answer.sources().get(1).id()).isEqualTo(2L);
        assertThat(answer.sources().get(1).score()).isEqualTo(0.80);

        // 记忆写入：user 原始问题 + assistant 实际回复（Advisor 显式装配后可断言）
        List<Message> mem = memory.get("u1");
        assertThat(mem).hasSize(2);
        assertThat(mem.get(0).getMessageType()).isEqualTo(MessageType.USER);
        assertThat(mem.get(0).getText()).isEqualTo("新生报到需要带什么材料？");
        assertThat(mem.get(1).getMessageType()).isEqualTo(MessageType.ASSISTANT);
        assertThat(mem.get(1).getText()).isEqualTo("根据手册，需要携带材料[1][2]");
    }

    @Test
    @DisplayName("U2 topK 夹取：null→配置默认10；1000→20；0/-5→1；正常值透传")
    void U2_topKClamped() {
        service.ask("u2a", "问题", null);
        service.ask("u2b", "问题", 1000);
        service.ask("u2c", "问题", 0);
        service.ask("u2d", "问题", -5);
        service.ask("u2e", "问题", 3);

        org.mockito.ArgumentCaptor<Integer> k = org.mockito.ArgumentCaptor.forClass(Integer.class);
        verify(milvusRestStore, times(5)).search(any(float[].class), k.capture());
        assertThat(k.getAllValues()).containsExactly(10, 20, 1, 1, 3);
    }

    @Test
    @DisplayName("U3 阈值过滤：score < score-threshold 的 hits 被丢弃，不计入 sources 与 prompt")
    void U3_thresholdFiltering() {
        when(milvusRestStore.search(any(float[].class), anyInt())).thenReturn(List.of(
                hit(1L, 0.90, "高分相关内容", "a.txt"),
                hit(2L, 0.20, "低分无关内容", "b.txt"),
                hit(3L, 0.80, "次高分内容", "c.txt")));

        ChatAnswer answer = service.ask("u3", "问题", null);

        assertThat(answer.sources()).extracting(ChatAnswer.SourceItem::index).containsExactly(1, 2);
        assertThat(answer.sources()).extracting(ChatAnswer.SourceItem::id).containsExactly(1L, 3L);
        String promptText = capturedMainPromptText();
        assertThat(promptText).contains("高分相关内容").contains("次高分内容").doesNotContain("低分无关内容");
    }

    @Test
    @DisplayName("U4 切片预算：单切片超 chunk-max-chars 截断；累计超 chunkBudget 停止并同步剔除 sources")
    void U4_chunkBudgetAndTruncation() {
        int chunkMax = 10;
        int budget = 12;
        // historyChars=0、message="问"=1 字符；chunkBudget = contextMax - 0 - systemChars - 1 = 12
        int contextMax = ChatService.SYSTEM_TEMPLATE.length() + 1 + budget;
        RagProperties customProps = new RagProperties(null, null,
                new RagProperties.Chat(5, 0.45, 20, true, chunkMax, contextMax, 1024));
        ChatService custom = newService(customProps);

        when(milvusRestStore.search(any(float[].class), anyInt())).thenReturn(List.of(
                hit(1L, 0.9, "甲".repeat(25), "a.txt"),
                hit(2L, 0.8, "乙".repeat(5), "b.txt")));

        ChatAnswer answer = custom.ask("u4", "问", null);

        assertThat(answer.sources()).hasSize(1);
        assertThat(answer.sources().get(0).index()).isEqualTo(1);
        assertThat(answer.sources().get(0).text()).isEqualTo("甲".repeat(10));
        String promptText = capturedMainPromptText();
        // 注意：System 模板自带示例"[1]、[2]"，故断言切片行格式"[2] 乙…"而非裸"[2]"
        assertThat(promptText).contains("[1] " + "甲".repeat(10));
        assertThat(promptText).doesNotContain("[2] 乙").doesNotContain("乙".repeat(5));
    }

    @Test
    @DisplayName("U5 查询改写成功：改写结果用于检索；记忆写入的是用户原始问题")
    void U5_rewriteUsedForSearchOriginalQuestionStored() {
        when(rewriteModel.call(any(Prompt.class))).thenReturn(response("改写后的独立查询"));

        service.ask("u5", "第一轮问题", null);
        verify(embeddingModel).embed("第一轮问题");

        service.ask("u5", "那时间呢", null);
        verify(embeddingModel).embed("改写后的独立查询");
        verify(embeddingModel, never()).embed("那时间呢");

        List<Message> mem = memory.get("u5");
        assertThat(mem).extracting(Message::getText)
                .containsSubsequence("第一轮问题", "回答", "那时间呢", "回答");
        assertThat(mem).extracting(Message::getText).doesNotContain("改写后的独立查询");
    }

    @Test
    @DisplayName("U6a 改写回退：rewrite 抛异常 → 回退原始问题检索，流程不中断")
    void U6a_rewriteExceptionFallsBack() {
        when(rewriteModel.call(any(Prompt.class))).thenThrow(new RuntimeException("rewrite down"));

        service.ask("u6", "第一轮", null);
        ChatAnswer answer = service.ask("u6", "那时间呢", null);

        assertThat(answer.reply()).isEqualTo("回答");
        verify(embeddingModel).embed("那时间呢");
    }

    @Test
    @DisplayName("U6b 改写回退：rewrite 输出为空 → 回退原始问题检索")
    void U6b_rewriteEmptyFallsBack() {
        when(rewriteModel.call(any(Prompt.class))).thenReturn(response("   "));

        service.ask("u6", "第一轮", null);
        ChatAnswer answer = service.ask("u6", "那时间呢", null);

        assertThat(answer.reply()).isEqualTo("回答");
        verify(embeddingModel).embed("那时间呢");
    }

    @Test
    @DisplayName("U6c 改写回退：rewrite 输出超 100 字符 → 回退原始问题检索")
    void U6c_rewriteTooLongFallsBack() {
        when(rewriteModel.call(any(Prompt.class))).thenReturn(response("超".repeat(150)));

        service.ask("u6", "第一轮", null);
        ChatAnswer answer = service.ask("u6", "那时间呢", null);

        assertThat(answer.reply()).isEqualTo("回答");
        verify(embeddingModel).embed("那时间呢");
    }

    // ---------------------------------------------------------------- 失败类

    @Test
    @DisplayName("U8 兜底A：过滤后为空 → 话术A、sources=[]、跳过主生成、路径②写入 user+话术A")
    void U8_fallbackANoRelevantContent() {
        when(milvusRestStore.search(any(float[].class), anyInt()))
                .thenReturn(List.of(hit(1L, 0.10, "低分内容", "a.txt")));

        ChatAnswer answer = service.ask("u8", "知识库外的问题", null);

        assertThat(answer.reply()).isEqualTo(ChatService.FALLBACK_NO_HIT);
        assertThat(answer.sources()).isEmpty();
        verify(mainModel, never()).call(any(Prompt.class));
        List<Message> mem = memory.get("u8");
        assertThat(mem).hasSize(2);
        assertThat(mem.get(0).getText()).isEqualTo("知识库外的问题");
        assertThat(mem.get(1).getText()).isEqualTo(ChatService.FALLBACK_NO_HIT);
    }

    @Test
    @DisplayName("U9 兜底B：检索有结果但 chunkBudget=0 → 话术B、路径②写入、不调用主 LLM")
    void U9_fallbackBBudgetExhausted() {
        // historyChars=0、message="问"=1 字符 → chunkBudget=0
        int contextMax = ChatService.SYSTEM_TEMPLATE.length() + 1;
        RagProperties customProps = new RagProperties(null, null,
                new RagProperties.Chat(5, 0.45, 20, true, 600, contextMax, 1024));
        ChatService custom = newService(customProps);
        // 检索有结果（≥阈值）
        when(milvusRestStore.search(any(float[].class), anyInt()))
                .thenReturn(List.of(hit(1L, 0.9, "相关内容", "a.txt")));

        ChatAnswer answer = custom.ask("u9", "问", null);

        assertThat(answer.reply()).isEqualTo(ChatService.FALLBACK_BUDGET_EXHAUSTED);
        assertThat(answer.sources()).isEmpty();
        verify(mainModel, never()).call(any(Prompt.class));
        List<Message> mem = memory.get("u9");
        assertThat(mem).hasSize(2);
        assertThat(mem.get(0).getText()).isEqualTo("问");
        assertThat(mem.get(1).getText()).isEqualTo(ChatService.FALLBACK_BUDGET_EXHAUSTED);
    }

    @Test
    @DisplayName("U10 主 LLM 抛异常：异常向上传播；gate①(前写入) → 记忆回滚无残留")
    void U10_mainLlmThrowsRollback() {
        when(mainModel.call(any(Prompt.class))).thenThrow(new RuntimeException("boom"));

        assertThatThrownBy(() -> service.ask("u10", "问题", null))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("boom");

        // gate① 结论：Advisor 在主调用前写入 user，异常时需回滚 → 记忆无本轮残留
        assertThat(memory.get("u10")).isEmpty();
    }

    @Test
    @DisplayName("U11 主 LLM 空白回复：客户端收话术C、sources=[]、日志 fallback=EMPTY_REPLY、记忆[U,A_empty]、下一轮正常")
    void U11_mainLlmEmptyReplyFallbackC() {
        when(mainModel.call(any(Prompt.class))).thenReturn(response(""));

        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ChatService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        ChatAnswer answer;
        try {
            answer = service.ask("u11", "问题", null);
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(answer.reply()).isEqualTo(ChatService.FALLBACK_EMPTY_REPLY);
        assertThat(answer.sources()).isEmpty();
        assertThat(appender.list)
                .anyMatch(e -> e.getLevel() == Level.INFO
                        && e.getFormattedMessage().contains("fallback=EMPTY_REPLY"));

        // gate② 结论（写入型）：记忆保留 [U, A_empty]，话术C 仅作为响应
        List<Message> mem = memory.get("u11");
        assertThat(mem).hasSize(2);
        assertThat(mem.get(0).getMessageType()).isEqualTo(MessageType.USER);
        assertThat(mem.get(1).getMessageType()).isEqualTo(MessageType.ASSISTANT);
        assertThat(mem.get(1).getText()).isEqualTo("");

        // 下一轮 ask 不受影响
        when(mainModel.call(any(Prompt.class))).thenReturn(response("恢复后的回答"));
        ChatAnswer next = service.ask("u11", "那时间呢", null);
        assertThat(next.reply()).isEqualTo("恢复后的回答");
    }

    @Test
    @DisplayName("U12 sessionId 校验：null/blank→生成UUID；非法字符/>64→IllegalArgumentException")
    void U12_sessionIdValidation() {
        ChatAnswer a1 = service.ask(null, "问题", null);
        assertThat(a1.sessionId()).matches("^[0-9a-fA-F-]{36}$");
        ChatAnswer a2 = service.ask("  ", "问题", null);
        assertThat(a2.sessionId()).matches("^[0-9a-fA-F-]{36}$");
        assertThat(a2.sessionId()).isNotEqualTo(a1.sessionId());

        assertThatThrownBy(() -> service.ask("abc$123", "问题", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.ask("a".repeat(65), "问题", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.history("abc$123"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.clear("abc$123"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("U13 message 校验：null/blank/>2000→IllegalArgumentException；边界值2000可通过")
    void U13_messageValidation() {
        assertThatThrownBy(() -> service.ask(null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.ask(null, "", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.ask(null, "   ", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.ask(null, "长".repeat(2001), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(service.ask(null, "长".repeat(2000), null).reply()).isEqualTo("回答");
    }

    @Test
    @DisplayName("U15 同会话并发串行：条纹锁保证主 LLM 调用并发峰值==1，记忆无交错")
    void U15_sameSessionConcurrentSerial() throws Exception {
        AtomicInteger inCall = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        AtomicInteger seq = new AtomicInteger();
        when(mainModel.call(any(Prompt.class))).thenAnswer(inv -> {
            int cur = inCall.incrementAndGet();
            maxConcurrent.accumulateAndGet(cur, Math::max);
            Thread.sleep(100);
            inCall.decrementAndGet();
            return response("回复" + seq.incrementAndGet());
        });

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ChatAnswer> f1 = pool.submit(() -> service.ask("u15", "问题一", null));
            Future<ChatAnswer> f2 = pool.submit(() -> service.ask("u15", "问题二", null));
            f1.get(10, TimeUnit.SECONDS);
            f2.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(maxConcurrent.get()).isEqualTo(1);

        // 记忆无交错：每个 user 后紧跟其 assistant 回复（先到者先写，顺序取实际提交结果）
        List<Message> mem = memory.get("u15");
        assertThat(mem).hasSize(4);
        String firstQuestion = mem.get(0).getText();
        assertThat(firstQuestion).isIn("问题一", "问题二");
        String secondQuestion = firstQuestion.equals("问题一") ? "问题二" : "问题一";
        assertThat(mem.get(1).getMessageType()).isEqualTo(MessageType.ASSISTANT);
        assertThat(mem.get(2).getText()).isEqualTo(secondQuestion);
        assertThat(mem.get(3).getMessageType()).isEqualTo(MessageType.ASSISTANT);
    }

    // ---------------------------------------------------------------- 辅助

    /** 捕获主 LLM 最近一次收到的 Prompt 全文（含 System 与历史注入）。 */
    private String capturedMainPromptText() {
        org.mockito.ArgumentCaptor<Prompt> captor = org.mockito.ArgumentCaptor.forClass(Prompt.class);
        verify(mainModel, atLeastOnce()).call(captor.capture());
        return captor.getValue().getInstructions().stream()
                .map(Message::getText)
                .collect(Collectors.joining("\n"));
    }

    private static ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private static Map<String, Object> hit(Long id, double distance, String text, String source) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("distance", distance);
        m.put("text", text);
        m.put("source", source);
        return m;
    }
}
