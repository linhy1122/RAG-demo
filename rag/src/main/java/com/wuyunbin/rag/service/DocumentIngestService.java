package com.wuyunbin.rag.service;

import com.wuyunbin.rag.config.RagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Paths;
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

    public DocumentIngestService(EmbeddingModel embeddingModel,
                                 MilvusRestStore milvusRestStore,
                                 RagProperties props) {
        this.embeddingModel = embeddingModel;
        this.milvusRestStore = milvusRestStore;
        this.props = props;
    }

    /**
     * 导入单个文档文件，返回向量化后写入的块数。
     */
    public int ingest(MultipartFile file) throws IOException {
        String source = resolveSource(file.getOriginalFilename());
        DocumentReader reader = new TikaDocumentReader(file.getResource());
        List<Document> rawDocs = reader.read();

        List<String> chunks = semanticSplit(rawDocs);
        log.info("Document [{}] read={}, chunks={}, source={}", file.getOriginalFilename(), rawDocs.size(), chunks.size(), source);

        int dimension = resolveDimension();
        milvusRestStore.ensureCollection(dimension);

        int batchSize = props.ingest().batchSize();
        int total = 0;
        List<String> batch = new ArrayList<>(batchSize);
        for (String chunk : chunks) {
            batch.add(chunk);
            if (batch.size() == batchSize) {
                total += embedAndInsert(batch, source);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            total += embedAndInsert(batch, source);
        }
        // 重新加载 collection，确保新写入的数据可被检索
        milvusRestStore.loadCollection();
        return total;
    }

    /**
     * 解析切片来源文件名：null/空白兜底 "unknown"，剥离路径仅保留文件名，超 256 字符截断。
     * <p>
     * Milvus VarChar.max_length 校验的是 UTF-8 字节；UTF-8 单字符最大 4 字节（增补字符），
     * 256 字符 × 4 = 1024 字节 ≤ max_length，因此按 256 字符粗截即可保证字节安全，无需字节级循环。
     * 包可见以便单元测试（U7）。
     */
    String resolveSource(String originalFilename) {
        String source = (originalFilename == null || originalFilename.isBlank())
                ? "unknown"
                : Paths.get(originalFilename).getFileName().toString();
        if (source.length() > 256) {
            source = source.substring(0, 256);
        }
        return source;
    }

    /** 包可见以便单元测试（语义切片逻辑覆盖）。 */
    List<String> semanticSplit(List<Document> documents) {
        List<String> chunks = new ArrayList<>();
        for (Document document : documents) {
            String text = document.getText();
            if (text == null || text.isBlank()) {
                continue;
            }

            List<String> sentences = List.of(text
                    .replaceAll("\\r\\n?", "\\n")
                    .split("(?<=[。！？.!?])|\\n+"))
                    .stream()
                    .map(String::trim)
                    .filter(sentence -> !sentence.isBlank())
                    .toList();
            if (sentences.isEmpty()) {
                continue;
            }

            List<float[]> sentenceVectors = embeddingModel.embed(sentences);
            StringBuilder current = new StringBuilder();
            for (int i = 0; i < sentences.size(); i++) {
                String sentence = sentences.get(i);
                boolean startsNewChunk = current.length() > 0
                        && (cosineSimilarity(sentenceVectors.get(i - 1), sentenceVectors.get(i)) < 0.72
                        || current.length() + sentence.length() > 1200);
                if (startsNewChunk) {
                    chunks.add(current.toString());
                    current.setLength(0);
                }
                if (current.length() > 0) {
                    current.append(' ');
                }
                current.append(sentence);
            }
            if (current.length() > 0) {
                chunks.add(current.toString());
            }
        }
        return chunks;
    }

    /** 包可见以便单元测试。 */
    double cosineSimilarity(float[] left, float[] right) {
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        int dimensions = Math.min(left.length, right.length);
        for (int i = 0; i < dimensions; i++) {
            dot += left[i] * right[i];
            leftNorm += left[i] * left[i];
            rightNorm += right[i] * right[i];
        }
        if (leftNorm == 0 || rightNorm == 0) {
            return 0;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
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

    private int embedAndInsert(List<String> texts, String source) {
        List<float[]> vectors = embeddingModel.embed(texts);
        List<Map<String, Object>> rows = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("text", texts.get(i));
            row.put("source", source);
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
