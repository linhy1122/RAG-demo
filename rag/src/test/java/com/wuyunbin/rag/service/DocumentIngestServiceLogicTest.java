package com.wuyunbin.rag.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * U7：source 字段处理（null→"unknown"；带路径剥离为文件名；>256 字符截断）。
 */
class DocumentIngestServiceLogicTest {

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

    private DocumentIngestService newService() {
        return new DocumentIngestService(null, null, null, null);
    }
}
