# RAG 三参数校准计划（topK / 相似度阈值 / chunk-max-chars）

## 一、目标

用真实基础设施（LM Studio `localhost:1234` + Milvus `localhost:19530`，collection `rag_xmut` 已导入并 load 两篇手册），通过 Spring Boot 集成测试，**对三个参数的全组合直接真实作答并评分**，求解最佳组合：

1. `rag.chat.top-k`（当前 `10`，夹取 `[1,20]`）
2. `rag.chat.score-threshold`（当前 `0.60`，COSINE 相似度门槛）
3. `rag.chat.chunk-max-chars`（当前 `600`，单条参考资料拼入 Prompt 的最大字符数）

- **调参集（dev）**：`qa-questions-招生手册.json`（10 题）→ 对每个参数组合真实作答评分，选出最优
- **验证集（val）**：`qa-questions-新生手册.json`（10 题）→ 用选定最佳组合验证泛化性

最终将最佳三元组回写 `application.properties`；并按需在 `qa-questions-招生手册.json` 中写入生成的真实答案。

## 二、现状分析（关键代码）

- 三个参数都在 `RagProperties.Chat` record：`topK(10)`、`scoreThreshold(0.60)`、`chunkMaxChars(600)`（[RagProperties.java:75-85](file:///d:\A\rag-xmut\rag\src\main\java\com\wuyunbin\rag\config\RagProperties.java)）
- 检索 + 阈值过滤 + 单条截断：`ChatService.doAsk` / `buildReferences`（[ChatService.java:138-149](file:///d:\A\rag-xmut\rag\src\main\java\com\wuyunbin\rag\service\ChatService.java)、[ChatService.java:288-304](file:///d:\A\rag-xmut\rag\src\main\java\com\wuyunbin\rag\service\ChatService.java)）
- `ChatService` 构造器注入 `@Qualifier("chatClient") ChatClient`、`ChatMemory`、`EmbeddingModel`、`MilvusRestStore`、`QueryRewriteService`、`props`（[ChatService.java:88-100](file:///d:\A\rag-xmut\rag\src\main\java\com\wuyunbin\rag\service\ChatService.java)）
- 现有测试细节：`ChatServiceTest` 用 Mock + 自定义参数实例（`new RagProperties(null,null,new RagProperties.Chat(...),null,null)`，见 U4/U9）；`ChatIntegrationTest` 用 `@SpringBootTest + MockMvc + @MockitoBean`。
- 本题要 **真实** EmbeddingModel / MilvusRestStore / LLM，故不 Mock，`@SpringBootTest` 自动装配这些 bean，**手工构造 `ChatService`** 以注入不同参数组合。

## 三、总体思路

**单一阶段、全组合扫描（真实调 LLM 作答，不分离检索阶段）：**

- 网格：`topK ∈ {5, 10, 15, 20}` × `scoreThreshold ∈ {0.50, 0.60, 0.70}` × `chunkMaxChars ∈ {600, 800, 1000}` = **36 个组合**。
- 对调参集每个组合、每个问题，用真实 `ChatService.ask()` 生成回答，按黄金关键词/兜底规则评分 → 组合级准确率排序取最优。
- 用最优组合在验证集 10 题作答评分，报告逐题结果，确认泛化 → 回写 `application.properties`。

**评分规则（确定性，由 expectedAnswer 派生，不依赖人工标注）：**

- `exact`：作答通过 = `reply` 包含该精确值（黄金关键词）。
- `summary`：把 expectedAnswer 按中英文标点切片段，取长度 ≥4 的去重黄金关键词；作答通过 = `reply` 覆盖 ≥ ceil(0.5×N) 个关键词。
- `absent`：正确 = 检索过滤后为空 → 返回兜底话术 A（`知识库中没有相关内容`）；若错放不相干块导致 LLM 幻觉作答则判定失败。
- 组合准确率 = 正确题数 / 10。
- 平手时取处理更稳妥（一段不相干/更小 topK/更省 token 就近区分）的组合，并列组合在报告中并列展示供人工定夺。

## 四、产出物与改动清单

> 不改生产业务代码；全部新增位于 `src/test` + 运行期报告。最后仅回写 application.properties 三个值。

### 新增 1：测试辅助类 `QaDataset`（`src/test/java/com/wuyunbin/rag/qa/QaDataset.java`）
- 用 `ObjectMapper` + `ClassPathResource` 解析两个 qa 手册 json。
- `record QRow(int id, String kind, String question, String expectedAnswer)`。
- 黄金关键词派生 `goldenKeywords()` 与 `boolean answerPasses(kind, reply)`。

### 新增 2：全组合扫描 + 验证测试 `QaParameterScanTest`（`src/test/java/com/wuyunbin/rag/qa/QaParameterScanTest.java`）
- `@SpringBootTest`；`@Autowired`：`ChatClient`（`@Qualifier("chatClient")`）、`ChatMemory`、`EmbeddingModel`、`MilvusRestStore`、`QueryRewriteService`。
- 每组合用 `new RagProperties(...)` 构造独立 `ChatService`；**每题独立 `sessionId`**，无记忆污染；`topK` 显式传入。
- **阶段一（dev 全组合扫）**：36 组合 × 10 题真实作答，写 `target/qa/full-sweep.md`：每组合准确率排序表 + 最优/并列组合逐题 PASS/FAIL 明细（含命中 source/id/score）。
- **阶段二（val 验证）**：用最优组合对验证集 10 题作答，写 `target/qa/validation.md` 逐题 PASS/FAIL + 汇总。

## 五、关键设计决策

1. **不 Mock、手工装配 ChatService**：`ChatService.props` 是构造时 `final`，无法运行时改 → 必须用自定义 `RagProperties` 各建实例。复用现有 bean（`chatClient @Primary`、真实 `ChatMemory`、`EmbeddingModel`、`MilvusRestStore`、`QueryRewriteService`）。
2. **单 collection 含两篇文档**：检索会命中另一手册的相似块，这正是阈值校准对象；`absent` 题靠门槛拦掉无关块。
3. **无历史单轮问答**：每题固定独立 sessionId、不追问；`queryRewrite` 在无历史时近似原问，统一走同一入口保持链路一致。
4. **结果落盘 `target/qa/*.md`** 便于 Read 逐题/逐组合核对后再定夺参数。
5. **参数候选以扫描结果为遵循**，最终回写由执行者按报告结论显式执行并交代依据。

## 六、执行步骤

1. 新建 `QaDataset`（读取两 json + 评分规则）。
2. 编写 `QaParameterScanTest`：实现 dev 36 组合全扫 + val 验证两阶段。
3. 运行 dev 全扫，读取 `full-sweep.md`，按准确率选最优（并列组合人工定夺）。
4. 用最优组合运行验证集，读取 `validation.md`；若显著劣化（疑似过拟合）再回调网格复扫。
5. 将最优三元组回写 `application.properties`（保留注释说明依据与实测表索引），并视需要把生成答案写入 `qa-questions-招生手册.json`。
6. 向用户汇报三个最佳值、组合排序表与逐题明细。

## 七、验证命令

```bash
# 需先启动 LM Studio(1234) 与 Milvus(19530)，collection rag_xmut 已导入并 load
cd d:\A\rag-xmut
mvn -q -pl rag test -Dtest=QaParameterScanTest
```
- 运行后读 `rag/target/qa/full-sweep.md` 与 `rag/target/qa/validation.md`。
- 回写参数后跑既有回归：`mvn -q -pl rag test`。

## 八、风险与备注

- **LLM 调用量**：dev 36×10=360 次 + val 10 次 ≈ **370 次**真实 LLM 调用，耗时可能较长（每次数秒），总时长或达 20~40 分钟；必要时缩小网格（topK×3 阈值×3 chunk）。
- **LLM 非确定性**：单题只跑一次可能有抖动；若最优与次优分差 ≤1 题，可对并列组合各复跑 1 次取稳。
- **超时**：LM Studio 首次加载握手偏慢，可临时调 `rag.chat.timeout-seconds`。
- **不改动** 现有 `ChatServiceTest`/`ChatIntegrationTest`，新增测试类独立。