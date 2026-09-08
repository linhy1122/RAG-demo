package com.wuyunbin.rag.service;

import tools.jackson.databind.JsonNode;
import com.wuyunbin.rag.config.RagProperties;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Milvus 3.0 客户端，通过 REST API v2（gRPC 端口 19530）访问。
 * <p>
 * 说明：Spring AI 内置的 MilvusVectorStore 基于 milvus-sdk-java 2.x，只能对接 Milvus 2.x
 * 服务端（3.0 起 proto/SDK 契约已不可互换）。因此这里直接使用官方 REST API v2。
 */
@Service
public class MilvusRestStore {

    private final RagProperties props;
    private final String collectionName;
    private final String vectorFieldName = "vector";

    public MilvusRestStore(RagProperties props) {
        this.props = props;
        this.collectionName = props.milvus().collection();
    }

    /**
     * 确保知识库 collection 已存在，不存在则创建，并加载进内存。
     *
     * @param dimension 向量维度
     */
    public void ensureCollection(int dimension) {
        if (!collectionExists()) {
            createCollection(dimension);
        }
        createIndexIfMissing();
        loadCollection();
    }

    /**
     * 为向量字段创建索引（加载前必须存在索引）。
     */
    private void createIndexIfMissing() {
        JsonNode resp = call("/v2/vectordb/indexes/list",
                Map.of("collectionName", collectionName));
        JsonNode indexNames = resp.get("data");
        if (indexNames == null || !indexNames.isArray() || indexNames.isEmpty()) {
            List<Map<String, Object>> indexParams = List.of(Map.of(
                    "fieldName", vectorFieldName,
                    "indexName", "vector_idx",
                    "indexType", "AUTOINDEX",
                    "metricType", "COSINE"));
            call("/v2/vectordb/indexes/create", Map.of(
                    "collectionName", collectionName,
                    "indexParams", indexParams));
        }
    }

    /**
     * 判断 collection 是否已存在（通过 list 接口，避免对不存在的 collection 调用 describe 报错）。
     */
    public boolean collectionExists() {
        JsonNode resp = call("/v2/vectordb/collections/list");
        JsonNode data = resp.get("data");
        if (data == null || !data.isArray()) {
            return false;
        }
        for (JsonNode item : data) {
            if (collectionName.equals(item.path("collectionName").asText(null))
                    || collectionName.equals(item.asText(null))) {
                return true;
            }
        }
        return false;
    }

    private void createCollection(int dimension) {
        List<Map<String, Object>> fields = new ArrayList<>();
        fields.add(Map.of(
                "fieldName", "id",
                "dataType", "Int64",
                "isPrimary", true,
                "autoId", true));
        fields.add(Map.of(
                "fieldName", "text",
                "dataType", "VarChar",
                "elementTypeParams", Map.of("max_length", "2048")));
        fields.add(Map.of(
                "fieldName", vectorFieldName,
                "dataType", "FloatVector",
                "elementTypeParams", Map.of("dim", String.valueOf(dimension))));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("autoId", true);
        schema.put("fields", fields);

        call("/v2/vectordb/collections/create",
                Map.of("collectionName", collectionName, "schema", schema));
    }

    public void loadCollection() {
        call("/v2/vectordb/collections/load",
                Map.of("collectionName", collectionName));
    }

    /**
     * 批量插入向量行。
     *
     * @return 插入条数
     */
    public int insert(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        JsonNode resp = call("/v2/vectordb/entities/insert",
                Map.of("collectionName", collectionName, "data", rows));
        JsonNode insertCount = resp.get("data");
        return insertCount != null ? insertCount.path("insertCount").asInt(rows.size()) : rows.size();
    }

    /**
     * 向量相似度检索。
     */
    public List<Map<String, Object>> search(float[] queryVector, int topK) {
        JsonNode resp = call("/v2/vectordb/entities/search", Map.of(
                "collectionName", collectionName,
                "data", List.of(queryVector),
                "annsField", vectorFieldName,
                "limit", topK,
                "outputFields", List.of("text")));

        List<Map<String, Object>> results = new ArrayList<>();
        JsonNode data = resp.get("data");
        if (data == null || !data.isArray()) {
            return results;
        }
        for (JsonNode item : data) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("distance", item.path("distance").asDouble());
            JsonNode id = item.get("id");
            row.put("id", id != null ? id.asLong() : null);
            row.put("text", item.path("text").asText(null));
            results.add(row);
        }
        return results;
    }

    /**
     * 查询前 N 条数据。
     */
    public List<Map<String, Object>> query(int limit) {
        JsonNode resp = call("/v2/vectordb/entities/query", Map.of(
                "collectionName", collectionName,
                "filter", "id >= 0",
                "limit", limit,
                "outputFields", List.of("*")));

        List<Map<String, Object>> results = new ArrayList<>();
        JsonNode data = resp.get("data");
        if (data == null || !data.isArray()) {
            return results;
        }
        for (JsonNode item : data) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", item.path("id").asLong());
            row.put("text", item.path("text").asText(null));
            results.add(row);
        }
        return results;
    }

    /**
     * 调用统一 REST API（空请求体），并检查返回码。
     */
    private JsonNode call(String path) {
        return call(path, Map.of());
    }

    /**
     * 调用统一的 REST API 并检查返回码。
     */
    private JsonNode call(String path, Object body) {
        // 每次构建短生命周期 RestClient，避免线程安全问题并保持配置简洁
        var restClient = org.springframework.web.client.RestClient.builder()
                .baseUrl(props.milvus().baseUrl())
                .build();
        JsonNode resp = restClient.post()
                .uri(path)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);
        if (resp == null) {
            throw new IllegalStateException("Milvus REST [" + path + "] returned empty response");
        }
        int code = resp.path("code").asInt(-1);
        if (code != 0) {
            throw new IllegalStateException("Milvus REST [" + path + "] failed, code=" + code
                    + ", message=" + resp.path("message").asText(""));
        }
        return resp;
    }
}