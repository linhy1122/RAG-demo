package com.wuyunbin.rag.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * U14：RagProperties 零配置可启动——紧凑构造器填充默认值，不 NPE。
 */
class RagPropertiesTest {

    @Test
    void zeroConfigFillsDefaults() {
        RagProperties p = new RagProperties(null, null, null, null, null);
        assertThat(p.chat()).isNotNull();
        assertThat(p.chat().topK()).isEqualTo(10);
        assertThat(p.chat().scoreThreshold()).isEqualTo(0.60);
        assertThat(p.chat().memoryMaxMessages()).isEqualTo(20);
        assertThat(p.chat().queryRewrite()).isTrue();
        assertThat(p.chat().chunkMaxChars()).isEqualTo(600);
        assertThat(p.chat().contextMaxChars()).isEqualTo(4000);
        assertThat(p.chat().maxTokens()).isEqualTo(1024);
        assertThat(p.chat().logRetrieval()).isTrue();
        assertThat(p.chat().logRetrievalMaxRaw()).isEqualTo(10);
        assertThat(p.clean()).isNotNull();
        assertThat(p.clean().enabled()).isTrue();
        assertThat(p.clean().tableMaxBytes()).isEqualTo(1500);
        assertThat(p.test()).isNotNull();
        assertThat(p.test().simulateEmbedFailure()).isFalse();
    }

    @Test
    void partialConfigKeepsDefaultsViaRecord() {
        // 即便 milvus/ingest 为 null（极端场景），chat 仍有默认值不 NPE
        RagProperties p = new RagProperties(null, null,
                new RagProperties.Chat(8, 0.5, 30, false, 500, 3000, 2048, true, 10), null, null);
        assertThat(p.chat().topK()).isEqualTo(8);
        assertThat(p.chat().queryRewrite()).isFalse();
        assertThat(p.milvus()).isNull();
        assertThat(p.ingest()).isNull();
    }
}
