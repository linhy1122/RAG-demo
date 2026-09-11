package com.wuyunbin.rag.service;

import com.wuyunbin.rag.config.RagProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ingest 兼容与原子性测试（C13/C14 + embed 失败路径），基于状态机解析器。
 */
class DocumentIngestServiceTest {

    private RagProperties props(boolean clearBeforeIngest, boolean cleanEnabled, boolean simulateEmbedFailure) {
        return new RagProperties(
                new RagProperties.Milvus("http://localhost:19530", "rag_xmut", 4),
                new RagProperties.Ingest(16, 5, clearBeforeIngest, 0.60),
                null,
                new RagProperties.Clean(cleanEnabled, 1500),
                new RagProperties.Test(simulateEmbedFailure));
    }

    private EmbeddingModel mockEmbedding() {
        EmbeddingModel em = mock(EmbeddingModel.class);
        when(em.embed(anyList())).thenAnswer(inv -> {
            List<?> list = inv.getArgument(0);
            return list.stream().map(x -> new float[4]).toList();
        });
        return em;
    }

    private MockMultipartFile txt(String content) {
        return new MockMultipartFile("file", "test.txt", "text/plain",
                content.getBytes(StandardCharsets.UTF_8));
    }

    // C13 clean.enabled=false：不调用 parser.parse（走 bypass 兼容路径）
    @Test
    void cleanDisabledDoesNotInvokeCleaner() throws Exception {
        EmbeddingModel em = mockEmbedding();
        MilvusRestStore store = mock(MilvusRestStore.class);
        MarkdownStateMachineParser parser = mock(MarkdownStateMachineParser.class);
        when(store.insert(anyList())).thenReturn(1);
        when(parser.bypass(anyString())).thenReturn(List.of(
                new MarkdownStateMachineParser.Chunk(1, MarkdownStateMachineParser.Chunk.ChunkType.TEXT, "", "整篇")));
        DocumentIngestService service = new DocumentIngestService(em, store, parser, props(true, false, false));

        service.ingest(txt("## 目录\n条目 01\n# 正文\n内容"));

        verify(parser, never()).parse(anyString());
        verify(store).tryDrop();
    }

    // C14 clearBeforeIngest=false：不清空，旧数据保留
    @Test
    void clearBeforeIngestFalseKeepsCollection() throws Exception {
        EmbeddingModel em = mockEmbedding();
        MilvusRestStore store = mock(MilvusRestStore.class);
        when(store.insert(anyList())).thenReturn(1);
        MarkdownStateMachineParser parser = new MarkdownStateMachineParser(props(false, true, false));
        DocumentIngestService service = new DocumentIngestService(em, store, parser, props(false, true, false));

        service.ingest(txt("普通正文内容。"));

        verify(store, never()).tryDrop();
        verify(store).ensureCollection(anyInt());
    }

    // embed 失败路径：异常在 tryDrop 之前抛出，旧数据不清空
    @Test
    void simulateEmbedFailureAbortsBeforeDrop() throws Exception {
        EmbeddingModel em = mockEmbedding();
        MilvusRestStore store = mock(MilvusRestStore.class);
        MarkdownStateMachineParser parser = new MarkdownStateMachineParser(props(true, true, false));
        DocumentIngestService service = new DocumentIngestService(em, store, parser, props(true, true, true));

        assertThatThrownBy(() -> service.ingest(txt("内容。")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("simulate-embed-failure");

        verify(store, never()).tryDrop();
        verify(store, never()).insert(anyList());
        // 未调用插入前，也不会 ensureCollection（embed 在 drop/ensure 之前）
        verify(store, never()).ensureCollection(anyInt());
        verify(em, never()).embed(anyList()); // 模拟开关在真正 embed 前就抛错
    }
}