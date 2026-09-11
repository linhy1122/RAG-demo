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
    private final MarkdownStateMachineParser parser;
    private final RagProperties props;

    public DocumentIngestService(EmbeddingModel embeddingModel,
                                 MilvusRestStore milvusRestStore,
                                 MarkdownStateMachineParser parser,
                                 RagProperties props) {
        this.embeddingModel = embeddingModel;
        this.milvusRestStore = milvusRestStore;
        this.parser = parser;
        this.props = props;
    }

    /**
     * 导入单个文档文件，返回向量化后写入的块数。
     * 流程：读取 →（可选）状态机清洗/分块 → 全量向量化（成功后）→ Drop 清空旧数据 → 重建集合 → 插入。
     */
    public int ingest(MultipartFile file) throws IOException {
        String source = resolveSource(file.getOriginalFilename());
        DocumentReader reader = new TikaDocumentReader(file.getResource());
        List<Document> rawDocs = reader.read();

        // ① 状态机整块切分：以两个标题之间为一块，TEXT 整块保留（仅超硬性上限兜底切分）
        List<MarkdownStateMachineParser.Chunk> chunks = new ArrayList<>();
        boolean clean = props.clean().enabled();
        for (Document doc : rawDocs) {
            String text = doc.getText();
            if (text == null || text.isBlank()) {
                continue;
            }
            if (clean) {
                chunks.addAll(parser.parse(text));
            } else {
                chunks.addAll(parser.bypass(text));
            }
        }
        log.info("Document [{}] read={}, chunks={}, source={}",
                file.getOriginalFilename(), rawDocs.size(), chunks.size(), source);

        // ② 模拟失败开关：在任何 embed/入库前抛错，旧数据不被清空（测试专用）
        if (props.test().simulateEmbedFailure()) {
            throw new IllegalStateException("simulate-embed-failure=true (test only)");
        }

        int dimension = resolveDimension();
        // ③ 全量向量化到内存；失败即返回，未清空 Milvus 旧数据
        List<float[]> allVectors = new ArrayList<>(chunks.size());
        for (List<MarkdownStateMachineParser.Chunk> batch : partition(chunks, props.ingest().batchSize())) {
            allVectors.addAll(embeddingModel.embed(
                    batch.stream().map(MarkdownStateMachineParser.Chunk::text).toList()));
        }

        // ④ embed 全部成功后，才清空并重建
        if (props.ingest().clearBeforeIngest()) {
            milvusRestStore.tryDrop();
        }
        milvusRestStore.ensureCollection(dimension);

        // ⑤ 分批插入，统一补 source/title
        int batchSize = props.ingest().batchSize();
        int total = 0;
        for (int i = 0; i < chunks.size(); i += batchSize) {
            int end = Math.min(i + batchSize, chunks.size());
            total += insertRows(chunks.subList(i, end), allVectors.subList(i, end), source);
        }
        milvusRestStore.loadCollection();
        return total;
    }

    /** 按固定大小切分列表（惰性视图）。 */
    static <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> parts = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            parts.add(list.subList(i, Math.min(list.size(), i + size)));
        }
        return parts;
    }

    /** 批量向量化后（已内联在 ingest 中），此处负责把 chunk+vector+source+title 组行并入库。 */
    private int insertRows(List<MarkdownStateMachineParser.Chunk> chunks, List<float[]> vectors, String source) {
        List<Map<String, Object>> rows = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            MarkdownStateMachineParser.Chunk c = chunks.get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("text", c.text());
            row.put("source", source);
            row.put("title", c.titleChain());
            row.put("vector", vectors.get(i));
            rows.add(row);
        }
        return milvusRestStore.insert(rows);
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

    /**
     * 检索与 query 最相似的 topK 条知识，并按相似度阈值过滤。
     */
    public Map<String, Object> search(String query, int topK) {
        float[] queryVector = embeddingModel.embed(query);
        List<Map<String, Object>> hits = milvusRestStore.search(queryVector, topK);
        double threshold = props.ingest().scoreThreshold();
        List<Map<String, Object>> filtered = hits.stream()
                .filter(h -> distance(h) >= threshold)
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", query);
        result.put("hits", filtered);
        return result;
    }

    /** 取单条命中记录的相似度值（Milvus distance，COSINE 越大越相似）。 */
    private double distance(Map<String, Object> hit) {
        Object d = hit.get("distance");
        return d instanceof Number n ? n.doubleValue() : 0.0;
    }

    /**
     * 返回知识库中前 N 条数据。
     */
    public List<Map<String, Object>> list(int limit) {
        return milvusRestStore.query(limit);
    }

    private int resolveDimension() {
        int configured = props.milvus().embeddingDimension();
        if (configured > 0) {
            return configured;
        }
        return embeddingModel.dimensions();
    }
}
