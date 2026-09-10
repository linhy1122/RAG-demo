package com.wuyunbin.rag.service;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * U7：source 字段处理（null→"unknown"；带路径剥离为文件名；>256 字符截断）。
 * 同时覆盖 semanticSplit / cosineSimilarity 纯逻辑，提升覆盖率。
 */
class DocumentIngestServiceLogicTest {

    private DocumentIngestService newService() {
        return new DocumentIngestService(null, null, null);
    }

    @Test
    void resolveSourceNull() {
        assertThat(newService().resolveSource(null)).isEqualTo("unknown");
    }

    @Test
    void resolveSourceBlank() {
        assertThat(newService().resolveSource("   ")).isEqualTo("unknown");
    }

    @Test
    void resolveSourceStripsPath() {
        assertThat(newService().resolveSource("a/b/c.txt")).isEqualTo("c.txt");
        assertThat(newService().resolveSource("C:\\dir\\手册.txt")).isEqualTo("手册.txt");
        assertThat(newService().resolveSource("plain.txt")).isEqualTo("plain.txt");
    }

    @Test
    void resolveSourceTruncatesOver256() {
        String longName = "x".repeat(300);
        String result = newService().resolveSource(longName);
        assertThat(result).hasSize(256);
    }

    @Test
    void resolveSourceBoundary256NotTruncated() {
        String name256 = "y".repeat(256);
        assertThat(newService().resolveSource(name256)).hasSize(256);
    }

    @Test
    void cosineSimilarityBasic() {
        DocumentIngestService s = newService();
        assertThat(s.cosineSimilarity(new float[]{1, 0}, new float[]{1, 0})).isEqualTo(1.0);
        assertThat(s.cosineSimilarity(new float[]{1, 0}, new float[]{0, 1})).isEqualTo(0.0);
        assertThat(s.cosineSimilarity(new float[]{1, 1}, new float[]{1, 1}))
                .isCloseTo(Math.sqrt(2) / Math.sqrt(2) * Math.sqrt(2) / Math.sqrt(2),
                        org.assertj.core.data.Percentage.withPercentage(0.1));
    }

    @Test
    void cosineSimilarityZeroVector() {
        assertThat(newService().cosineSimilarity(new float[]{0, 0}, new float[]{1, 1})).isEqualTo(0.0);
    }

    @Test
    void semanticSplitChineseSentenceBoundary() {
        EmbeddingModel em = org.mockito.Mockito.mock(EmbeddingModel.class);
        try {
            org.mockito.Mockito.when(em.embed(org.mockito.ArgumentMatchers.anyList())).thenReturn(List.of(
                    new float[]{1, 0}, new float[]{1, 0}, new float[]{1, 0}));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        DocumentIngestService s = new DocumentIngestService(em, null, null);
        Document doc = new Document("第一句。第二句！第三句？");
        List<String> chunks = s.semanticSplit(List.of(doc));
        assertThat(chunks).isNotEmpty();
        assertThat(String.join("", chunks)).contains("第一句").contains("第二句").contains("第三句");
    }

    @Test
    void semanticSplitBlankTextSkipped() {
        // 空白文本在向量化前即被跳过（EmbeddingModel 不会被调用）
        DocumentIngestService s = new DocumentIngestService(
                org.mockito.Mockito.mock(EmbeddingModel.class), null, null);
        assertThat(s.semanticSplit(List.of(new Document("   ")))).isEmpty();
    }
}
