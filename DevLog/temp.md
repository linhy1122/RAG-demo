spring.application.name=rag

# ============ Spring AI 2.0 - OpenAI 兼容接口 LM Studio ============
# LM Studio 本地服务地址
spring.ai.openai.base-url=http://localhost:1234/v1
# LM Studio 不校验 API Key，任意值即可
spring.ai.openai.api-key=lm-studio
# 对话模型 google/gemma-4-e4b
spring.ai.openai.chat.options.model=google/gemma-4-e4b
spring.ai.openai.chat.options.temperature=0.2
# 向量模型 text-embedding-bge-m3
spring.ai.openai.embedding.options.model=text-embedding-bge-m3

# ============ Knife4j / OpenAPI 文档 ============
knife4j.enable=true
knife4j.setting.language=zh_cn
springdoc.api-docs.path=/v3/api-docs
springdoc.swagger-ui.path=/swagger-ui.html

# ============ Milvus 3.0 REST API v2 ============
# Milvus 服务地址（REST API v2 监听在 gRPC 端口 19530）
rag.milvus.base-url=http://localhost:19530
# 目标 collection（删除后重建）
# Milvus 集合：由程序自动创建；若不存在会先删除再建（不校验是否已存在）
rag.milvus.collection=rag_xmut
# 向量维度：0 = 启动时自动探测向量模型输出维度
rag.milvus.embedding-dimension=0

# ============ Document ingestion ============
# 每个批次处理的文档（上传+解析）数量
rag.ingest.batch-size=16
# 检索时默认返回的 topK
rag.ingest.default-top-k=5
# 检索结果最低分（/search 接口的 topK 过滤），distance >= 该值才保留
rag.ingest.score-threshold=0.60
# 入库前是否先清空 Drop 重建 collection（可选操作）：
# 若 embed 阶段失败，会删除已写入的数据。
# 默认 false：保留已有数据，不清空旧数据。
# 建议首次导入时设为 true 以清空旧 collection，避免旧数据残留。
rag.ingest.clear-before-ingest=false

# ============ Document cleaning ============
# 入库前清理文档（去除空白/重复段落 + 修复编码问题）
rag.clean.enabled=true
# 单个表格最大字节数（UTF-8），超过则按行拆分（表头+分隔行修复后保留）。
# 与 Milvus text 字段 max_length=2048 对齐，默认 1500 字符左右；超出则截断，保证不超过 1800，防止写入失败。
rag.clean.table-max-bytes=1500
# rag.test.* 仅用于测试：模拟 embed 失败，默认关闭
rag.test.simulate-embed-failure=false

# ============ File upload ============
spring.servlet.multipart.max-file-size=50MB
spring.servlet.multipart.max-request-size=50MB

# ============ Multi-turn QA ============
# 每个问题默认检索的 chunk 数（可被请求参数 topK 覆盖，范围 [1,20]）。
# 调优依据：实测中精确日期 chunk 在改写后的追问里排第 7-9 位（改写措辞略有不同），
# 原设定 5 会漏掉，10 可覆盖观测到的范围。
rag.chat.top-k=10
# 相似度阈值（COSINE，越大越相似）。调优依据（case 10）：
# 相关 chunk 得分 0.65-0.85，无关 chunk（如天气）0.49-0.54，故 0.60 作为分界。
rag.chat.score-threshold=0.60
# 会话记忆窗口（保留最近 N 条消息，含用户+助手及兜底轮次）
rag.chat.memory-max-messages=20
# 是否启用多轮查询改写
rag.chat.query-rewrite=true
# 单个 chunk 拼接进 prompt 的最大字符数
rag.chat.chunk-max-chars=600
# 检索上下文总字符预算（会被历史/系统模板/用户问题动态压缩；
# 耗尽时回退到"会话过长"回复，不保证总 prompt 上限）
rag.chat.context-max-chars=4000
# 每次回答最大 token 数（由 ChatConfig 读取；可观察实际回答长度后调整，
# 分条回答可能被截断）
rag.chat.max-tokens=1024
# 主 LLM 响应读取超时（秒），由 ChatConfig 中的 RestClientCustomizer 应用；
# 若网关验证显示 Customizer 未被 OpenAI 自动配置识别，
# 则此配置无效，应移除该 Customizer Bean。
rag.chat.timeout-seconds=60
# 是否在 INFO 级别打印每个检索 chunk（id/score/source/text）及检索耗时；
# 调试阶段建议 true，便于排查；生产环境建议 false，减少日志量（含 chunk/回答内容）。
rag.chat.log-retrieval=true
# 每个问题最多打印多少条原始（过滤前）命中；超出的汇总为 "raw 命中 K 条"
rag.chat.log-retrieval-max-raw=10