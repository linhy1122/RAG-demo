package com.wuyunbin.rag.service;

import com.wuyunbin.rag.config.RagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档导入与检索服务：
 * 读取文档 → 切分 → 向量化 → 写入 Milvus。
 */
@Service
public class DocumentIngestService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestService.class);

    private final EmbeddingModel embeddingModel;
    private final MilvusRestStore milvusRestStore;
    private final RagProperties props;
    private final TokenTextSplitter splitter;

    public DocumentIngestService(EmbeddingModel embeddingModel,
                                 MilvusRestStore milvusRestStore,
                                 RagProperties props) {
        this.embeddingModel = embeddingModel;
        this.milvusRestStore = milvusRestStore;
        this.props = props;
        this.splitter = TokenTextSplitter.builder()
                .withChunkSize(200)
                .withMinChunkSizeChars(50)
                .withMinChunkLengthToEmbed(5)
                .withKeepSeparator(true)
                .build();
    }

    /**
     * 导入单个文档文件，返回向量化后写入的块数。
     */
    public int ingest(MultipartFile file) throws IOException {
        DocumentReader reader = new TikaDocumentReader(file.getResource());
        List<Document> rawDocs = reader.read();

        List<Document> chunks = splitter.apply(rawDocs).stream()
                .filter(d -> d.getText() != null && !d.getText().isBlank())
                .toList();
        log.info("Document [{}] read={}, chunks={}", file.getOriginalFilename(), rawDocs.size(), chunks.size());

        int dimension = resolveDimension();
        milvusRestStore.ensureCollection(dimension);

        int batchSize = props.ingest().batchSize();
        int total = 0;
        List<String> batch = new ArrayList<>(batchSize);
        for (Document chunk : chunks) {
            batch.add(chunk.getText());
            if (batch.size() == batchSize) {
                total += embedAndInsert(batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            total += embedAndInsert(batch);
        }
        // 重新加载 collection，确保新写入的数据可被检索
        milvusRestStore.loadCollection();
        return total;
    }

    /**
     * 检索与 query 最相似的 topK 条知识。
     */
    public Map<String, Object> search(String query, int topK) {
        float[] queryVector = embeddingModel.embed(query);
        List<Map<String, Object>> hits = milvusRestStore.search(queryVector, topK);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", query);
        result.put("hits", hits);
        return result;
    }

    /**
     * 返回知识库中前 N 条数据。
     */
    public List<Map<String, Object>> list(int limit) {
        return milvusRestStore.query(limit);
    }

    private int embedAndInsert(List<String> texts) {
        List<float[]> vectors = embeddingModel.embed(texts);
        List<Map<String, Object>> rows = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("text", texts.get(i));
            row.put("vector", vectors.get(i));
            rows.add(row);
        }
        return milvusRestStore.insert(rows);
    }

    private int resolveDimension() {
        int configured = props.milvus().embeddingDimension();
        if (configured > 0) {
            return configured;
        }
        return embeddingModel.dimensions();
    }
}
