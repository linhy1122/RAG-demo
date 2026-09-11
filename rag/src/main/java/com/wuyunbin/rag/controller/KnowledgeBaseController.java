package com.wuyunbin.rag.controller;

import com.wuyunbin.rag.config.RagProperties;
import com.wuyunbin.rag.service.DocumentIngestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库管理接口：文档导入、相似度检索、数据查看。
 */
@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
@Tag(name = "知识库接口", description = "文档导入与向量检索")
public class KnowledgeBaseController {

    private final DocumentIngestService ingestService;
    private final RagProperties props;

    @PostMapping("/ingest")
    @Operation(summary = "导入文档", description = "上传文档（txt/pdf/docx 等），自动切分、向量化并写入 Milvus")
    public Map<String, Object> ingest(@RequestParam("file") MultipartFile file) throws IOException {
        int count = ingestService.ingest(file);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("filename", file.getOriginalFilename() == null ? "unnamed" : file.getOriginalFilename());
        result.put("collection", props.milvus().collection());
        result.put("inserted", count);
        result.put("status", "ok");
        return result;
    }

    @GetMapping("/search")
    @Operation(summary = "相似度检索", description = "基于向量语义检索知识库中最相似的内容")
    public Map<String, Object> search(@RequestParam String query,
                                      @RequestParam(defaultValue = "-1") int topK) {
        int k = topK <= 0 ? props.ingest().defaultTopK() : topK;
        return ingestService.search(query, k);
    }

    @GetMapping("/list")
    @Operation(summary = "数据列表", description = "查看知识库中前 N 条向量化数据")
    public List<Map<String, Object>> list(@RequestParam(defaultValue = "10") int limit) {
        return ingestService.list(limit);
    }
}