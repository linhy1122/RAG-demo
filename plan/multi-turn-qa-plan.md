# 多轮问答（RAG + 会话记忆）接口计划

> 状态：**已实施完成**（2026-09-10 联调验收通过，gate 结论 / score 分布 / 校准值见 §11 实施记录）
> 日期：2026-09-10
> 项目：d:\A\rag-xmut（Spring Boot 4.1.1 + Spring AI 2.0 + Milvus 3.0.1 REST v2 + LM Studio）

## 0. 修订记录

### v9 意见对照（评审修复）

| 评审点 | 处理方式 | 落点章节 |
|---|---|---|
| 1. 话术 C 缺失，U11 断言悬空 | 新增话术 C"回答生成失败，请稍后重试或换个问法"，仅用于"主 LLM 调用成功但 reply 空白"；LLM 抛异常仍走 5xx 不进兜底。§3/§6.3/§6.5/§8.1 U11/§8.3 ④d 同步对齐 | §3、§6.3、§6.5、§8.1、§8.3 |
| 2. U1 单测记忆断言立不住 | 单测构建 ChatClient 时显式挂 `MessageChatMemoryAdvisor`（真实内存 ChatMemory 实例），与生产装配一致；U1 直接断言 `chatMemory.get()` | §8.0、§8.1 |
| 3. 覆盖率 30% 可行性 | 已实测：主代码 571 行（MilvusRestStore 206 行占 36%，DocumentIngestService 167 行），改造后预计 ~970 行；MilvusRestStore 明确纳入 JaCoCo excludes（HTTP 细节由切片 B 联调覆盖），口径写入验收记录 | §8.4、§9 |
| 4. I2 Mock 依赖自动配置细节 | §8.0 说明补充：实施时第一步验证 @MockitoBean 覆盖后 ChatClient.Builder 是否绑定 Mock，未绑定则用 @TestConfiguration 显式覆盖 ChatClient Bean | §8.0 |
| 5. U15 并发验证不可靠 | 改为 AtomicInteger 并发峰值法（call 进入计数 + 100ms 睡眠，断言 maxConcurrent==1），弃用总耗时断言 | §8.1 |
| 6. 小问题 | §3"防止膨胀"改"保持对话连贯"；§8.4 配置补 excludes；§4.3/§4.4 注明未知 sessionId 不报错；§8.0 措辞改为"pom 已显式声明 starter" | 相关章节 |
| 7. 对账复核（第二轮评审 1~5） | 评审所称 5 处不一致经逐条 grep 对账：U15/§3 兜底B措辞/④d/§4.3 **确认已在正文**（评审基于修订过程的中间快照）；§8.0 两条说明、§8.4 excludes+可行性口径、§3 流程图话术C 因编辑并发覆盖丢失，**本轮已恢复**并全部终验 | §3、§8.0、§8.4 |
| 8. 新增：日志 EMPTY_REPLY（第二轮评审 6~9） | §8.3 fallback 枚举补 EMPTY_REPLY 区分空回复与兜底A/B；U11、④d 断言同步增加"日志 fallback=EMPTY_REPLY"；§3 流程图 ⑤ 分支明确话术C 与 5xx | §8.3、§8.1、§3 |

### v8 新增（测试分层/覆盖率/验收标准，摘要）

测试分三层（单元 Mock 全外部｜集成切片A @SpringBootTest+MockMvc+@MockitoBean｜切片B 真实联调）；单元测试 U1~U15 与集成测试 I1~I11 按成功/失败类分组；JaCoCo LINE ≥30% 硬卡点；§8.5 独立验收清单（功能/质量/交付物）；§4.1 补 Content-Type 说明。

### v7 意见对照

| 评审点 | 处理方式 | 落点章节 |
|---|---|---|
| 1. chunkBudget=0 复用"知识库中没有"话术是撒谎 | 拆分两种兜底：无相关内容 → "知识库中没有相关内容"；预算耗尽 → "当前会话上下文较长，请清空会话或开启新会话再提问"，各自写入记忆 | §3、§6.3、§8 |
| 2. "保证总预算不被突破"不成立（历史不受预算控制） | 表述改为"在历史可控范围内不再拼入切片，避免进一步推高"；真正保证总预算需 token 窗口，属 §10 暂不做范围，计划不再声称已做到 | §6.3、§10 |
| 3. gate 需补：Advisor 在**空回复**时是否写入 | gate 扩为四项：异常时写入时机、空回复时写入行为（决定方案 B 论证）、ChatMemory API 面、Customizer 拾取+model 默认值；gate 结论落定前 §6.5 取舍文字标注"以 gate 结论为准" | §6.5、§8、§9 |
| 4. systemChars 定义歧义 | 明确定义为"System Prompt **不含【参考资料】部分**的固定模板长度（代码常量字符串）"，防止把切片长度重复扣减 | §6.3 |
| 5. 改写历史含空 assistant | 改写取最近 4 条前过滤空白 assistant 消息 | §6.2 |
| 6. source 截断 while 为死代码 | 删除 while，仅保留 256 字符粗截（256×4字节上限=1024，含增补字符场景仍安全，注释说明） | §4.2 |
| 7. Customizer 无效时的清理 | 配置注释补"若无效，删除该 Customizer Bean" | §7 |

### v2~v6 意见对照（摘要）

v2：Advisor 显式挂载｜改写与记忆隔离｜阈值过滤+空结果跳过主生成｜topK 透传｜sources 可追溯+schema 迁移｜COSINE 统一 score｜history 仅窗口｜sessionId 校验｜串行锁｜双层截断｜改写回退｜配置默认值｜DTO 兼容｜可断言验收
v3：兜底手动写记忆（双写入路径）｜双 ChatClient 隔离与 @Primary｜Advisor 时机先验证｜条纹锁｜父级配置 null 防护｜动态上下文预算｜参数校验｜source 迁移细节｜显式 model｜阈值起步 0.45｜历史编号说明
v4：maxTokens 走配置｜rewrite client @Value 空默认｜sessionId blank 规则｜Paths.get 取文件名｜三操作共锁｜测试④拆分｜超时落点｜第 0 步硬性 gate｜System 实际长度预算｜drop 后自动建 collection｜Integer topK｜空 reply 处理｜阈值校准前不上调｜ChatMemory 冲突实测为准｜estTokens 日志
v5：RestClientCustomizer 替代 Builder Bean（Milvus 自建实例不受影响，已核实）｜source 按 UTF-8 字节截断+max_length 1024｜移除机制依 ChatMemory API 实况｜OpenAiChatModel 类型注入｜原子性约定｜测试④用 LM Studio 请求日志｜estTokens 中文×1.0｜max-tokens 标注观察调整
v6：historyChars 与改写解耦（一次 get 两个用途）｜drop 提前到 schema 改动前｜空 reply 方案 B 不移除（删除 removeLastTurn）｜测试④b 简化断言｜source 截断 O(n)｜chunkBudget 无下限保护（总预算优先）｜timeout 失效标注｜gate 三项

## 1. 目标

在已有知识库（文档已切片向量化导入 Milvus collection `rag_xmut`）的基础上，提供**多轮问答接口**：

1. 提问时自动从 Milvus 检索相关切片，回答基于知识库内容（RAG），并返回可追溯的引用来源。
2. 支持**多轮对话**：记住会话上下文，追问能被正确理解；**兜底轮次同样计入记忆**。
3. 会话可查询历史（记忆窗口内）、可清空。
4. 知识库外问题稳定兜底，不编造。

## 2. 现状分析

| 模块 | 现状 | 与目标的差距 |
|---|---|---|
| `ChatController` / `ChatService` | `POST /api/chat` 纯单轮，无记忆、无检索 | 需升级为多轮 + RAG |
| `MilvusRestStore.search(float[], int topK)` | 已支持动态 topK，返回 distance/text/id；**RestClient 自建实例（`RestClient.builder()` 直调），不注入 Spring Builder** | 小改：schema 与 outputFields 增加 `source` 字段 |
| `DocumentIngestService` | 切片写入 text+vector；`ingest()` 首次上传即调 `ensureCollection()` | 小改：insert 行携带文件名到 `source` 字段 |
| `ChatConfig` | 仅一个裸 ChatClient Bean | 改造为两个 ChatClient（带/不带 Memory Advisor）+ 超时 Customizer |
| 记忆存储 | 无 | 用 Spring AI 内置 ChatMemory（内存实现） |

## 3. 总体方案

```
客户端(带 sessionId + message)
   │
   ▼
ChatController  POST /api/chat  ← 参数校验；ask/history/clear 共用条纹锁
   │
   ▼
ChatService.ask()
   │
   ├─① sessionId null/blank → 生成 UUID（新会话）；非 blank 才校验正则
   │
   ├─② 读取历史（一次 get，两个用途互不干扰）
   │     allHistory = chatMemory.get(sessionId)        ← 全窗口，用于字符预算
   │     最近 4 条（过滤空白 assistant）                 ← 仅用于查询改写
   │
   ├─③ 查询改写（隔离调用，绝不污染正式记忆）
   │     rewriteChatClient（手动构建、无 Advisor、temperature=0.1、model 可选显式）
   │     → 失败/为空/超长(>100字符) → 回退原始问题检索
   │     （无历史直接用原问题，跳过 LLM 调用）
   │     ⚠ 用户消息不预写入 ChatMemory
   │
   ├─④ 检索（Integer topK：null 取配置，最终夹取 [1,20]）
   │     embeddingModel.embed(改写后query) → milvusRestStore.search(vec, topK)
   │     → score 过滤：COSINE，score < score-threshold 丢弃
   │     ├─ 过滤后为空（检索确无相关内容）
   │     │    → 兜底话术A"知识库中没有相关内容，请换个问法" + 路径②写入
   │     ├─ 有结果但 chunkBudget=0（检索到但历史挤占预算）
   │     │    → 兜底话术B"当前会话上下文较长，请清空会话或开启新会话再提问"
   │     │      + 路径②写入（保持对话连贯，引导用户清空会话）
   │     └─ 有结果且有预算 → 截断拼 Prompt（§6.3）
   │
   ├─⑤ 主 ChatClient 调用（Memory Advisor，maxTokens/超时走配置）
   │     成功且 reply 非空白 → Advisor 自动写入（路径①），正常返回
   │     成功但 reply 空白 → 客户端收到兜底话术C（sources=[]）；
   │                        记忆按 gate② 实测行为处理（§6.5），不做移除
   │     异常 → 5xx 错误响应；按 gate 实测写入行为处理（预期无需回滚）
   │
   ▼
返回 { sessionId, reply, sources[] }
```

**记忆写入路径（关键约定）**：

| 路径 | 触发条件 | 写入方 | 写入内容 |
|---|---|---|---|
| ① Advisor 自动写 | 主 ChatClient 调用成功（gate②=写入型时，空 reply 也写入 A_empty） | MessageChatMemoryAdvisor | user 原始问题 + assistant 实际回复 |
| ② 手动写 | 跳过主生成 LLM 的两种兜底轮：检索无相关内容 / 预算耗尽 | `chatMemory.add(...)` | user 原始问题 + 对应兜底话术（A 或 B） |
| ③ 条件手动写（依 gate②） | 主调用成功但 reply 空白，且 gate② 实测 Advisor 跳过写入 | `chatMemory.add(...)` | user 原始问题 + 话术C（避免孤立 user） |

路径①②互斥，同一轮消息只写入一次；路径③仅在 gate② 证明 Advisor 跳过写入时启用，与①互斥（gate②=写入型时记忆保留 [U, A_empty]，话术C 仅作为响应、不写入）。

## 4. 接口设计

### 4.1 提问（核心）

`POST /api/chat`（Content-Type: application/json，编码 UTF-8）

请求体：

```json
{
  "sessionId": "可选；首次不传或为空白，服务端生成；追问时回传",
  "message": "新生报到需要带什么材料？",
  "topK": "可选，Integer；null 时取 rag.chat.top-k，最终夹取 [1,20]"
}
```

**sessionId 规则**：
- `sessionId == null || sessionId.isBlank()` → 生成新 UUID，不校验；
- 非 blank → 按 `^[a-zA-Z0-9_-]{1,64}$` 校验，非法返回 400。
- `GET /history`、`DELETE` 的路径变量非空必传，同样正则校验。

`message`：非空且 ≤2000 字符，超限 400。DTO 中 `topK` 用 **Integer**（非 int）。

响应体：

```json
{
  "sessionId": "uuid",
  "reply": "根据入学手册，需要携带……[1][2]",
  "sources": [
    { "index": 1, "id": 42, "score": 0.72, "source": "jmu新生手册.txt", "text": "报到时须携带……" },
    { "index": 2, "id": 87, "score": 0.65, "source": "jmu新生手册.txt", "text": "……" }
  ]
}
```

兜底轮次响应：`reply` = 话术 A 或 B，`sources` = `[]`。

### 4.2 sources 可追溯性约定

| 字段 | 说明 |
|---|---|
| `index` | Prompt 中的编号（[1][2]…），与 reply 引用标注、数组顺序一一对应 |
| `id` | Milvus 切片主键 |
| `score` | COSINE 相似度，**越大越相似**（不使用 distance 命名） |
| `source` | 来源文件名（仅文件名，不含路径） |
| `text` | 拼入 Prompt 的切片文本（含截断，与 Prompt 一致） |

**source 取值逻辑（null 安全 + 剥离路径 + 截断）**：

> Milvus `VarChar.max_length` 校验的是 **UTF-8 字节**。UTF-8 单字符最大 4 字节（增补字符），256 字符 × 4 = 1024 字节 ≤ max_length，因此**只需按 256 字符粗截即可保证字节安全**，无需字节级循环。

```java
String original = file.getOriginalFilename();
String source = (original == null || original.isBlank())
        ? "unknown"
        : Paths.get(original).getFileName().toString();
if (source.length() > 256) {          // 256×4字节=1024，保证不超 VarChar max_length
    source = source.substring(0, 256);
}
```

**Schema 迁移**：

1. `createCollection` schema 新增：`{"fieldName": "source", "dataType": "VarChar", "elementTypeParams": {"max_length": "1024"}}`。
2. `search` 的 `outputFields` 增加 `source`。
3. 迁移命令（drop 后**无需手动建 collection**——已核实 `DocumentIngestService.ingest()` 首次上传即调用 `ensureCollection()`，按新 schema 自动创建并加载）：

```bash
curl -X POST http://localhost:19530/v2/vectordb/collections/drop \
  -H "Content-Type: application/json" -d '{"collectionName": "rag_xmut"}'
# 之后直接重新上传即可：POST /api/knowledge/ingest
```

### 4.3 查看会话历史（辅助）

`GET /api/chat/{sessionId}/history`，返回 `[{"role": "user|assistant", "content": "..."}]`。

> 明确语义：**仅返回当前记忆窗口内**（最近 `memory-max-messages` 条，含兜底轮次、可能的空 assistant 消息）消息，非完整历史；清空后不可查。与 ask/clear 共用条纹锁，不会读到中间状态。**不存在的 sessionId 返回空数组，不报 404/500**（与 §8.2 I8 对齐）。

### 4.4 清空会话（辅助）

`DELETE /api/chat/{sessionId}`，删除该会话记忆。与 ask 共用条纹锁。**不存在的 sessionId 幂等返回 200，不报错**（与 §8.2 I8 对齐）。

> `POST /api/chat` 路径不变，JSON 保留 `reply` 字段名，新增 sessionId/sources，向后兼容。

## 5. Prompt 设计

System Prompt（示意；§6.3 的 systemChars 指**不含【参考资料】部分**的固定模板长度）：

```
你是"入学咨询助手"，请仅依据下面提供的【参考资料】回答用户问题。
- 回答简洁、准确，使用中文。
- 引用资料时在对应句子末尾标注编号，如 [1]、[2]。
- 历史对话中出现的 [数字] 编号仅对应当时那一轮的资料，与本轮无关；
  本轮回答的编号请以当前【参考资料】为准。
- 如果参考资料不足以回答，请直接说明"知识库中没有相关内容"，不要编造。
【参考资料】
[1] {切片1（截断后）}
[2] {切片2}
```

- User 消息保留用户原始问题（非改写后的检索词）。
- 历史消息由 Memory Advisor 注入。

## 6. 关键实现细节

### 6.1 两个 ChatClient 的装配（超时落点 / 注入方式）

```java
@Configuration
public class ChatConfig {

    @Bean
    @Primary
    public ChatClient chatClient(ChatClient.Builder builder,
                                 MessageChatMemoryAdvisor memoryAdvisor,
                                 RagProperties props) {
        return builder
                .defaultAdvisors(memoryAdvisor)          // 显式注册，不会自动生效
                .defaultOptions(OpenAiChatOptions.builder()
                        .maxTokens(props.chat().maxTokens())   // 走配置，不硬编码
                        .build())
                .build();
    }

    @Bean
    public ChatClient rewriteChatClient(
            OpenAiChatModel chatModel,               // 按具体类型注入，不用 @Qualifier
            @Value("${spring.ai.openai.chat.options.model:}") String model) {
        OpenAiChatOptions.Builder opts = OpenAiChatOptions.builder().temperature(0.1);
        if (!model.isBlank()) {
            opts.model(model);     // 未配置时留空，回退 ChatModel 默认 options，启动不失败
        }
        return ChatClient.builder(chatModel)   // 手动构建，避开 Builder 状态共享污染
                .defaultOptions(opts.build())
                .build();
    }

    /**
     * 超时定制：RestClientCustomizer 仅作用于"注入式"自动配置 Builder。
     * 本项目唯一注入方是 Spring AI OpenAI 自动配置；MilvusRestStore 自建实例不受影响（已核实）。
     * 实际是否被拾取由第 0 步 gate 验证；不生效则删除本 Bean，接受默认超时。
     * 不设 connect timeout（模型首次加载握手可能偏紧）。
     */
    @Bean
    public RestClientCustomizer lmStudioReadTimeoutCustomizer(
            @Value("${rag.chat.timeout-seconds:60}") int timeoutSeconds) {
        return builder -> {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
            builder.requestFactory(factory);
        };
    }
}
```

- 若容器中出现多个 `ChatModel` 实现导致 `OpenAiChatModel` 类型注入仍歧义，实施期打印 `getBeanNamesForType(ChatModel.class)` 核实后再加 @Qualifier。
- 所有 ChatClient 注入点显式 `@Qualifier`（主 client 用 `@Qualifier("chatClient")`）。
- **ChatMemory Bean**：自定义 Bean 必须存在；自动装配是否退避以**启动日志实测为准**，验证容器中仅一个 ChatMemory Bean。
- 备选：若双 Bean 装配仍有问题，rewrite 退化为直接调用 `ChatModel.call()`。

### 6.2 查询改写与兜底

- 历史**只取最近 4 条**参与改写（不用全窗口 20 条），且**过滤掉空白 assistant 消息**（避免上一轮空回复在改写 prompt 中出现空行，影响改写质量）。此读取仅服务改写，与字符预算计算（§6.3 全窗口独立求和）互不影响。
- Prompt：`"根据对话历史，把用户最新问题改写成一个独立、自包含的检索查询。只输出改写后的查询本身，不要任何解释、引号或多余文字。"`
- `try-catch` 全包裹：超时/异常/为空/输出>100 字符 → 回退原始问题检索，warn 日志，不阻塞主流程。
- `rag.chat.query-rewrite=false` 可整体关闭（跳过改写，字符预算照常独立计算）。

### 6.3 检索、过滤与上下文预算

- **topK**：`Integer` 请求参数，null → `rag.chat.top-k`，最终夹取 [1,20]。ChatService 直接注入 `EmbeddingModel` + `MilvusRestStore`（已核实签名支持动态 topK）。
- **阈值过滤**：COSINE 下 `score >= score-threshold` 才保留。**校准前保持 0.45 不上调**，依据 §8 用例 ⑩ 的 score 分布校准后再固化。
- **两种兜底（语义区分，不复用话术）**：

| 分支 | 判定条件 | 返回话术 | 记忆写入 |
|---|---|---|---|
| 无相关内容 | score 过滤后为空 | A："知识库中没有相关内容，请换个问法" | user + 话术A |
| 预算耗尽 | 检索有结果但 chunkBudget=0 | B："当前会话上下文较长，请清空会话或开启新会话再提问" | user + 话术B |
| 生成失败（空回复） | 主 LLM 调用成功但内容空白 | C："回答生成失败，请稍后重试或换个问法" | gate②=写入型：保留 [U, A_empty]，话术C 仅作响应；gate②=跳过型：路径③写入 user + 话术C |

A、B 跳过主生成 LLM，sources 为空数组；话术 B 写入记忆是为了保持对话连贯并引导用户清空会话（并非阻止历史增长）。C 发生在主生成之后，sources 同样置空（回复不存在，引用无意义）。LLM 抛异常不属兜底，走 5xx 错误响应，不产生话术。

- **历史字符与改写解耦（独立计算全窗口）**：

```java
List<Message> allHistory = chatMemory.get(sessionId);   // 全窗口（一次读取）
int historyChars = allHistory.stream()
        .mapToInt(m -> m.getText() == null ? 0 : m.getText().length())
        .sum();
// 改写另用 allHistory 的最近 4 条（过滤空白 assistant，见 §6.2），两者互不影响
```

- **切片预算（准确语义：在历史可控范围内不再拼入切片，避免进一步推高）**：

```
systemChars  = System Prompt 不含【参考资料】部分的固定模板长度（代码中常量字符串，
               切片长度不计入，避免与 chunkBudget 循环依赖/重复扣减）
chunkBudget  = max(0, context-max-chars − historyChars − systemChars − 用户问题长度)
```

- chunkBudget = 0（历史极长的极端情况）→ 不拼任何切片，走**话术 B**（非"知识库中没有"）。
- 0 < chunkBudget：按序拼接切片，单切片超 `chunk-max-chars`（默认 600）截断；累计超 `chunkBudget` 停止，未拼入的切片从 sources 同步剔除，保证编号一致。
- ⚠ **准确表述**：historyChars 来自 Memory Advisor 注入的历史，不受 context-max-chars 控制，窗口满且消息长时仅 historyChars 就可能超过总预算。本机制做到的是"预算耗尽后不再追加切片"，**不声称保证总 prompt 不超限**；真正保证需 token 级历史裁剪，属 §10 暂不做范围。
- **估算 token（中文不低估）**：`estTokens ≈ CJK字符数 × 1.0 + 其余字符数 ÷ 4`，日志输出，后续决定是否升级为 token 窗口。
- **配置默认值**（父级 null 防护）：

```java
@ConfigurationProperties(prefix = "rag")
public record RagProperties(Milvus milvus, Ingest ingest, Chat chat) {
    public RagProperties {                       // 紧凑构造器：rag.chat.* 全未配置时不 NPE
        if (chat == null) {
            chat = new Chat(5, 0.45, 20, true, 600, 4000, 1024);
        }
    }
    public record Chat(
            @DefaultValue("5") int topK,
            @DefaultValue("0.45") double scoreThreshold,
            @DefaultValue("20") int memoryMaxMessages,
            @DefaultValue("true") boolean queryRewrite,
            @DefaultValue("600") int chunkMaxChars,
            @DefaultValue("4000") int contextMaxChars,
            @DefaultValue("1024") int maxTokens) {
    }
}
```

验收含"零配置可启动"测试：注释掉全部 `rag.chat.*` 后应用正常启动且问答可用。

### 6.4 DTO 与兼容

- 新建 `ChatAnswer`（sessionId / reply / sources），避开与 Spring AI 自身 `ChatResponse` 同名。
- JSON 保留 `reply` 字段名，旧客户端不破坏。

### 6.5 记忆写入、锁与异常处理

**两条记忆写入路径**见 §3 表格，互斥不重复。

**条纹锁（ask / history / clear 三操作共用，无泄漏无竞态）**：

```java
private static final int STRIPES = 64;
private final ReentrantLock[] locks = IntStream.range(0, STRIPES)
        .mapToObj(i -> new ReentrantLock()).toArray(ReentrantLock[]::new);

private ReentrantLock lockFor(String sessionId) {
    return locks[(sessionId.hashCode() & 0x7fffffff) % STRIPES];
}
```

ask 全流程、`history()`、`clear()` 均在对应条纹锁内执行，history 不会读到中间态。

**空 reply 的取舍（方案 B：不移除；论证以 gate 结论为准）**：

- 主 ChatClient 调用成功但 reply 空白：**保留** Advisor 已写入的内容，不做任何移除；客户端收到兜底话术C（sources=[]）。
- ⚠ 方案 B 依赖 gate 实测结论（§9 第 0 步）：
    - 若 gate 确认空回复时 Advisor 仍写入 `[U, A_empty]` → 方案 B 成立：残留一条空 assistant 对下一轮影响很小；而移除在窗口已满时会**永久丢失最早对话**（窗口淘汰不可逆），两害相权选不移除。
    - 若 gate 发现空回复时 Advisor **跳过写入**（记忆中只有孤立 U）→ 方案 B 论证失效，届时改为：空白 reply 视同兜底，走路径③手动写入 user+话术C，避免孤立 user 消息挂在历史末尾。
- 不实现任何消息移除逻辑（ChatMemory 无单条移除 API；get→subList→clear→重写仅在 gate 证明必要时才引入）。

**异常处理**：

| 情况 | 处理 |
|---|---|
| LLM 调用抛异常 | 预期 Advisor 为"成功后写入"（gate 验证），异常时本轮消息本就未入记忆，**不写回滚代码**；若 gate 实测为 before 写入 → 再补充回滚方案（届时 get→subList→clear→重写，接受窗口淘汰不可逆） |

### 6.6 参数与会话治理

| 项 | 规则 |
|---|---|
| sessionId | null/blank → 生成 UUID；非 blank 才校验 `^[a-zA-Z0-9_-]{1,64}$`，非法 400 |
| message | 非空、≤2000 字符，超限 400 |
| topK | Integer，null 取配置，夹取 [1,20] |
| history 接口语义 | 仅窗口内消息（§4.3） |
| 适用范围 | 内存记忆，**仅内网临时使用**；TTL/最大会话数/持久化暂不做（Spring AI 内置 Jdbc/Redis Repository 可平滑升级） |

## 7. 配置项（application.properties 新增）

```properties
# ============ 多轮问答 ============
# 每次提问默认检索条数（可被请求参数 topK 覆盖，最终夹取 [1,20]）
rag.chat.top-k=5
# 相似度阈值（COSINE，越大越相似）；校准前保持 0.45 不上调
rag.chat.score-threshold=0.45
# 会话记忆窗口（保留最近 N 条消息，含 user+assistant 与兜底轮次）
rag.chat.memory-max-messages=20
# 是否启用多轮查询改写
rag.chat.query-rewrite=true
# 单切片拼入 Prompt 的最大字符数
rag.chat.chunk-max-chars=600
# 检索上下文总字符预算（被历史/系统模板/用户问题动态扣减；预算耗尽走"会话过长"兜底，
# 不声称保证总 prompt 不超限）
rag.chat.context-max-chars=4000
# 单次回答 maxTokens 上限（ChatConfig 读取此配置；默认值实施时按实际回答长度观察后调整，
# 分点列举类回答可能被截断）
rag.chat.max-tokens=1024
# 主生成 LLM 响应读超时（秒），落在 ChatConfig 的 RestClientCustomizer；
# 若第 0 步 gate 验证 Customizer 未被 OpenAI 自动配置拾取，本配置无效，
# 且应删除该 Customizer Bean（避免无关 Bean 留在容器）
rag.chat.timeout-seconds=60
```

## 8. 测试计划与验收标准

**前置硬性 gate**：§9 实施步骤第 0 步完成后才能开始业务编码。

### 8.0 测试分层与工具

| 层级 | 工具 | 依赖外部服务 | 运行方式 |
|---|---|---|---|
| 单元测试 | JUnit 5 + Mockito + AssertJ（pom.xml 已显式声明 `spring-boot-starter-webmvc-test`，test scope，自带上述工具与 MockMvc，无新依赖） | 否（全部 Mock） | `mvn test` |
| 集成测试（切片 A） | `@SpringBootTest` + MockMvc + `@MockitoBean` | 否（真实 Spring 上下文 + 内存 ChatMemory；EmbeddingModel / MilvusRestStore / OpenAiChatModel Bean 覆盖为 Mock） | `mvn test` |
| 集成测试（切片 B 联调） | HTTP 客户端按 §8.3 用例执行 | 是（真实 Milvus + LM Studio） | 最终验收阶段执行 |
| 覆盖率 | JaCoCo maven 插件（版本由 spring-boot-starter-parent 管理） | — | `mvn verify`，LINE ≥30% 硬卡点 |

说明：

- Spring Boot 4.x 使用 `org.springframework.test.context.bean.override.mockito.MockitoBean`（旧 `@MockBean` 已移除）。
- ChatClient 链式 API 不可直接 Mock：单元测试先 Mock `ChatModel.call(Prompt)` 返回固定内容，再围绕该 Mock 构建真实 ChatClient，并**显式挂载 `new MessageChatMemoryAdvisor(测试用真实内存 ChatMemory)`**（与生产 ChatConfig 装配方式一致）——否则 U1 的"记忆新增"断言无从成立。
- 集成测试 Bean 覆盖不确定点：主 ChatClient 由 `ChatClient.Builder` 自动配置构建，`@MockitoBean` 覆盖 `OpenAiChatModel` 后 Builder 是否绑定 Mock **实施时第一步验证**（打印实际生效的 ChatModel 类型）；若未绑定，改用 `@TestConfiguration` 显式覆盖 ChatClient Bean（绕过自动配置），确保集成测试不会打到真实 LM Studio。
- MilvusRestStore 的 HTTP 细节不在单测范围，由切片 B 联调覆盖。

### 8.1 单元测试用例（无外部服务）

**成功类：**

| 编号 | 被测点 | 断言 |
|---|---|---|
| U1 | ask 主流程 happy path | 返回 reply + sources；index 从 1 连续；score 与 Milvus 返回一致；`chatMemory.get(sessionId)` 新增 user（原始问题）+ assistant（依赖 §8.0 显式装配的 Advisor） |
| U2 | topK 夹取 | null→配置默认；1000→20；0/-5→1；正常值透传 |
| U3 | 阈值过滤 | score < score-threshold 的 hits 被丢弃，不计入 sources 与 prompt |
| U4 | 切片预算拼接 | 单切片超 chunk-max-chars 截断；累计超 chunkBudget 停止；未拼入切片从 sources 同步剔除，编号连续一致 |
| U5 | 查询改写成功 | 有历史时改写结果用于检索；记忆写入的是用户原始问题（非改写词） |
| U6 | 改写回退 | rewrite 抛异常/空输出/>100 字符 → 回退原始问题检索，warn 日志，流程不中断 |
| U7 | source 字段处理 | null→"unknown"；带路径剥离为文件名；>256 字符截断 |
| U8 | 兜底 A（无相关内容） | 过滤后为空 → reply=话术A、sources=[]、跳过主生成 LLM、路径②写入 user+话术A |
| U9 | 兜底 B（预算耗尽） | 检索有结果但 chunkBudget=0 → reply=话术B、路径②写入、不调用主 LLM |

**失败类：**

| 编号 | 被测点 | 断言 |
|---|---|---|
| U10 | 主 LLM 抛异常 | 异常向上传播（由控制器/全局处理转 5xx）；依 gate① 结论验证记忆无残留 |
| U11 | 主 LLM 空白回复 | 客户端收到**话术C**、sources=[]、日志 fallback=EMPTY_REPLY；依 gate② 结论验证记忆（写入型 [U, A_empty] / 跳过型 user+话术C）；下一轮 ask 不受影响 |
| U12 | sessionId 校验 | null/blank→生成 UUID 不报错；含非法字符或 >64 字符→IllegalArgumentException（控制器转 400） |
| U13 | message 校验 | null/blank/>2000 字符→IllegalArgumentException |
| U14 | RagProperties 零配置 | `new RagProperties(null, null, null)` 紧凑构造器填充默认值，无 NPE |
| U15 | 同会话并发串行 | Mock `ChatModel.call()` 进入时 `AtomicInteger` 计数并睡眠 100ms、记录并发峰值；两线程先后 ask 同一 sessionId，断言 `maxConcurrent == 1`；辅以记忆顺序严格为 [U1, A1, U2, A2] 无交错（总耗时断言因 JIT/GC 抖动不可靠，弃用） |

### 8.2 集成测试用例（@SpringBootTest + MockMvc，外部协作 Mock）

**成功类：**

| 编号 | 请求 | 断言 |
|---|---|---|
| I1 | `POST /api/chat` 首轮（不传 sessionId） | 200；sessionId 为合法 UUID；reply 非空；sources 每项含 index/id/score/source/text 五字段 |
| I2 | `POST /api/chat` 追问（带 I1 的 sessionId） | 200；sessionId 不变；Mock LLM 收到的 prompt 含历史消息（验证 Advisor 生效） |
| I3 | `GET /api/chat/{sessionId}/history` | 200；返回窗口内 role/content 序列，含兜底轮次 |
| I4 | `DELETE /api/chat/{sessionId}` | 200；之后 history 返回空数组；再 ask 视为新会话语境 |

**失败类（常见请求失败）：**

| 编号 | 请求 | 断言 |
|---|---|---|
| I5 | sessionId 含非法字符（`abc$123`）或超 64 字符 | 400，错误信息明确 |
| I6 | message 为 3000 字符 | 400 |
| I7 | message 为 `""` 或字段缺失 | 400 |
| I8 | history/clear 使用不存在的 sessionId | history 返回空数组、clear 幂等返回 200（不 404/500） |
| I9 | EmbeddingModel Mock 抛异常 | 5xx；响应体不含内部堆栈 |
| I10 | 检索 hits 全被阈值过滤 | 200；reply=话术A、sources=[]（兜底而非报错） |
| I11 | 非法 JSON / Content-Type 错误 | 400 |

### 8.3 联调验收用例（真实 Milvus + LM Studio，最终验收执行）

结构化日志（每次提问一行，供断言与阈值校准）：

```
[chat] sessionId=xxx, raw=原始问题, rewritten=改写后query, topK=n, scores=[id:score,...], used=n, historyChars=n, systemChars=n, chunkBudget=n, estTokens≈n, fallback=NONE|NO_HIT|BUDGET_EXHAUSTED|EMPTY_REPLY
```

| 用例 | 操作 | 可验证断言 |
|---|---|---|
| ⓪ 零配置启动 | 注释全部 rag.chat.* 启动 | 应用正常启动，问答可用（默认值生效，无 NPE） |
| ⓪ gate 四项 | ① Advisor **异常时**写入行为 ② Advisor **空回复时**写入行为（写入 U+A_empty 还是跳过）③ ChatMemory API 面核实（仅 add/get/clear）④ RestClientCustomizer 拾取验证 + model 空值默认行为 | 结论写入实施记录：②决定 §6.5 方案 B 文字是否成立，①决定异常回滚是否需要 |
| ① 首轮提问 | 不传 sessionId 问"新生报到需要带什么材料？" | 返回新 sessionId；reply 含材料内容；sources index 从 1 连续；scores 非空 |
| ② 多轮追问 | 带 sessionId 问"那具体时间是几号？" | 断言：rewritten 含"报到时间/时间"关键词或 hits 命中报到时间切片；reply 上下文连贯 |
| ③ 改写开关对照 | `query-rewrite=false` 重复② | rewritten=原始问题，②类追问 hits 劣化（记录对比）；historyChars 仍正确计算 |
| ④ 首轮知识库外问题 | **首轮无历史**问"今天天气如何" | 断言：used=0、fallback=NO_HIT、reply=话术A、sources=[]、**LM Studio 请求日志计数为零次新请求**（先记录预热基线） |
| ④b 带历史的兜底 | 有历史后再问知识库外问题 | 断言（简化，不验证 LLM 请求数）：fallback=NO_HIT、sources=[]、记忆含本轮 user+话术A |
| ④c 兜底记忆连续性 | ④ 之后同会话追问"那明天呢？" | 断言：history 含 ④ 的 user+assistant 两条（路径②生效）；改写能读到"天气"上下文 |
| ④d LLM 空回复 | 模拟主 LLM 返回空白内容 | 断言（依 gate ② 结论）：客户端收到**话术C**、sources=[]、日志 fallback=EMPTY_REPLY；记忆中无孤立 user（写入型有 A_empty，跳过型有话术C）；下一轮追问正常 |
| ⑤ topK 透传 | 请求传 `"topK": 2` / `"topK": 1000` | 日志 topK=2 / topK=20（null 取配置、越界夹取均生效） |
| ⑥ 历史窗口 | 提问超过 memory-max-messages 后查 history | 条数=窗口大小，为最近消息 |
| ⑦ 清空会话与并发 | DELETE 后追问；ask 进行中并发 DELETE / history | rewritten=原始问题；无"清空后被写回"；history 无中间态 |
| ⑧ 非法参数 | sessionId 100 字符 / message 3000 字符 / sessionId="" | 前两者 400；**空字符串生成新会话（不 400）** |
| ⑨ 异常行为 | 停 LM Studio 后提问 | 返回 5xx；恢复后追问，历史无"有问无答"残留（依据 gate ① 结论） |
| ⑩ score 分布 | 跑完①②④后汇总日志 scores | 记录相关/无关切片 score 区间，据此固化 score-threshold（固化前不上调） |
| ⑪ 中文长文件名 | 上传带中文长文件名的文档再提问 | sources 的 source 字段完整显示且未超 1024 字节 |
| ⑫ 预算耗尽 | 构造超长历史使 chunkBudget=0 | 断言：fallback=BUDGET_EXHAUSTED、reply=**话术B（会话过长，而非"知识库中没有"）**、sources=[]、记忆含话术B；之后清空会话可恢复正常问答 |
| ⑬ 改写过滤空 assistant | ④d 之后同会话追问 | 断言：改写 prompt 历史部分不含空 assistant 行（日志/调试断点验证） |

### 8.4 覆盖率要求（≥30%）

`pom.xml` 新增插件（版本由 spring-boot-starter-parent 管理；若与 Java 21 不兼容再显式固定 0.8.13+）：

```xml
<plugin>
    <groupId>org.jacoco</groupId>
    <artifactId>jacoco-maven-plugin</artifactId>
    <executions>
        <execution>
            <goals><goal>prepare-agent</goal></goals>
        </execution>
        <execution>
            <id>report</id>
            <phase>test</phase>
            <goals><goal>report</goal></goals>
        </execution>
        <execution>
            <id>check</id>
            <phase>verify</phase>
            <goals><goal>check</goal></goals>
            <configuration>
                <includes>
                    <include>com.wuyunbin.rag.**</include>
                </includes>
                <excludes>
                    <exclude>com/wuyunbin/rag/RagApplication.class</exclude>
                    <exclude>com/wuyunbin/rag/service/MilvusRestStore.class</exclude>
                </excludes>
                <rules>
                    <rule>
                        <element>BUNDLE</element>
                        <limits>
                            <limit>
                                <counter>LINE</counter>
                                <value>COVEREDRATIO</value>
                                <minimum>0.30</minimum>
                            </limit>
                        </limits>
                    </rule>
                </rules>
            </configuration>
        </execution>
    </executions>
</plugin>
```

- 统计范围：仅 `com.wuyunbin.rag.**`；报告输出 `target/site/jacoco/index.html`。
- 达标口径：`mvn verify` 构建通过 = LINE 覆盖率 ≥30%（BUNDLE 级）。
- **可行性核实（2026-09-10 实测行数）**：主代码现共 571 行，其中 MilvusRestStore 206 行（36%）、DocumentIngestService 167 行、其余（controller/config/dto/service）约 200 行；本特性改造后预计总量 950~1000 行。MilvusRestStore 的 HTTP 细节由切片 B 联调覆盖、不进入 JaCoCo 统计，故明确纳入 excludes 并在验收记录注明口径。排除后需覆盖约 230 行即达 30%，ChatService（预计 ~200 行）+ QueryRewriteService + ChatController + RagProperties 的 U/I 用例预计覆盖远超该值；若不足，补充 DocumentIngestService 纯逻辑单测（semanticSplit / cosineSimilarity，Mock EmbeddingModel）兜底，不引入 MockWebServer 等新依赖。
- 原则：不靠大面积 excludes 凑数；excludes 仅限 `RagApplication` 与 `MilvusRestStore` 两项，均在验收记录中注明理由。

### 8.5 验收标准（以下全部满足方为实施完成）

**功能验收：**

1. §8.1 单元测试全部通过（U1~U15）。
2. §8.2 集成测试全部通过（I1~I11）。
3. §8.3 联调用例全部通过（⓪~⑬），其中：
    - 零配置启动（⓪）通过，gate 四项结论写入实施记录；
    - score 分布（⑩）已记录并固化 score-threshold；
    - 三种兜底语义正确：知识库外→话术A，预算耗尽→话术B，空回复→话术C，不混淆；
    - 多轮连贯：追问用例②能结合历史命中正确切片。
4. 异常恢复：⑨ 停 LM Studio 恢复后，后续问答正常、历史无残留。

**质量验收：**

5. `mvn verify` 一次性通过，含 JaCoCo LINE ≥30% 硬卡点。
6. Knife4j 文档页可正常打开并展示提问/历史/清空三个端点。

**交付物：**

7. 实施记录：gate 四项结论、score 分布、estTokens 观察值、score-threshold 固化值。
8. 接口自测记录：成功 + 400 + 兜底三类请求-响应示例各至少一份。

## 9. 改动清单与实施步骤

### 改动清单

| 文件 | 操作 | 内容 |
|---|---|---|
| `config/ChatConfig.java` | **修改** | 双 ChatClient（@Primary 主 + rewrite 按 `OpenAiChatModel` 类型注入手动构建）；maxTokens 走 RagProperties；`RestClientCustomizer` 读超时（gate 无效则删） |
| `config/ChatMemoryConfig.java` | 新建 | ChatMemory Bean（MessageWindowChatMemory，窗口走配置）+ MessageChatMemoryAdvisor Bean |
| `service/ChatService.java` | 改造 | ask 主流程；条纹锁（ask/history/clear 共用）；写入路径①②③（③依 gate②）+ 三种兜底话术（A/B/C）；历史读取一次两用（全窗口预算 + 最近 4 条改写并过滤空 assistant）；空 reply 不移除；动态上下文预算 |
| `service/QueryRewriteService.java` | 新建 | 独立 rewriteChatClient，仅取最近 4 条历史（过滤空白 assistant），失败回退 |
| `dto/ChatRequest.java` | 修改 | 增加 sessionId、`Integer topK` |
| `dto/ChatAnswer.java` | 新建 | sessionId / reply / sources（index/id/score/source/text） |
| `dto/ChatResponse.java` | 删除 | 被 ChatAnswer 取代（reply 字段名保留） |
| `controller/ChatController.java` | 修改 | 参数校验（sessionId blank 规则/message/topK）；新增 history、clear 端点 |
| `config/RagProperties.java` | 修改 | 新增 Chat 子 record（紧凑构造器 + @DefaultValue） |
| `application.properties` | 修改 | 新增 rag.chat.* 八项（含 timeout-seconds） |
| `service/MilvusRestStore.java` | 小改 | schema 增加 source（VarChar max_length=1024）；search 返回 source |
| `service/DocumentIngestService.java` | 小改 | insert 行写 `Paths.get(...).getFileName()`，null 兜底，256 字符截断 |
| `pom.xml` | 小改 | 新增 JaCoCo 插件（版本由 parent 管理），verify 阶段 LINE ≥30% 卡点（excludes：RagApplication、MilvusRestStore），无新依赖 |

### 实施步骤（顺序执行；**drop 必须在 schema 改动生效前**）

| 步骤 | 内容 | 性质 |
|---|---|---|
| **0** | **gate 四项验证**：① Advisor 异常时写入行为 ② Advisor 空回复时写入行为 ③ ChatMemory API 面 ④ Customizer 拾取 + model 默认值 | **硬性 gate：未完成不进入业务编码** |
| 1 | RagProperties + application.properties 配置（含零配置启动测试） | — |
| 2 | ChatMemoryConfig + ChatConfig（双 ChatClient + Customizer） | — |
| 3 | **先 drop 旧 collection（§4.2 命令）** → MilvusRestStore/DocumentIngestService source 字段小改 → **重新上传一份测试文档作 fixture**（触发 ensureCollection 按新 schema 建表，此后步骤可做集成自测） | 顺序硬约束 |
| 4 | QueryRewriteService + ChatService 主流程（条纹锁、双写入路径、两种兜底话术、预算解耦计算） | 依据 gate ①② 结论处理异常/空回复 |
| 5 | DTO + ChatController 三端点 | — |
| 6 | 编写单元测试（U1~U15）+ 集成测试（I1~I11）→ `mvn test` 全绿 → 配置 JaCoCo → `mvn verify` 通过，覆盖率 ≥30% | §8.1、§8.2、§8.4 |
| 7 | 全量重新上传文档 → 按 §8.3 联调用例验收（含 score 分布校准、中文文件名、预算耗尽、改写过滤用例）→ 对照 §8.5 验收清单逐项确认 | — |

## 10. 暂不做（明确边界）

- 流式输出（SSE）：后续增强。
- 记忆持久化（JDBC/Redis Repository）、TTL/最大会话数、完整历史：本期内存实现，仅内网临时使用。
- **基于 token 的历史裁剪/窗口**：当前机制只在预算耗尽后停止追加切片，不控制历史本身的大小（historyChars 可能超预算，见 §6.3 准确表述）；真正的总预算保证依赖此项，视 estTokens 运行数据决定是否实施。
- 多知识库路由 / 元数据过滤：单 collection 满足当前需求。

## 11. 实施记录（2026-09-10）

### 11.1 gate 四项结论（决定 §6.5 / §6.1 方案）

| gate | 结论 | 对实施的影响 |
|---|---|---|
| ① Advisor 异常时写入行为 | 异常前 user 已写入记忆（记忆=[孤立 U]，无 assistant） | ChatService ask 异常路径**需要回滚** user 写入（已实现） |
| ② Advisor 空回复时写入行为 | 写入 [U, A_empty]（**写入型**），客户端拿到空串 | 话术C 场景记忆为 [U, A_empty]，话术C 本身不重复写入；§6.5 方案 B（空 reply 不移除）成立 |
| ③ ChatMemory API 面 | 仅 add/get/clear，窗口内存实现可用 | 按计划使用，无障碍 |
| ④ Customizer 拾取 | `RestClientCustomizer` 未被 OpenAI 自动配置拾取 | 改用 `OpenAiHttpClientBuilderCustomizer` 设置读超时（已实现，rag.chat.timeout-seconds=60 生效） |

验证载体：`src/test/java/com/wuyunbin/rag/gate/GateAdvisorBehaviorTest.java`（4 个用例，stdout 打印记忆快照）。

### 11.2 联调 score 分布与阈值固化（用例⑩）

真实环境（bge-m3 嵌入 + gemma-4-e4b 生成，`jmu新生手册.txt` 503 切片）：

| 查询类型 | score 区间（top 命中） | 样本 |
|---|---|---|
| 相关（报到材料/时间/日期等 6 组提问） | **0.645 ~ 0.854** | 含改写轮与直问轮 |
| 无关（"今天天气如何"/"那明天呢？" 2 组） | **0.491 ~ 0.547** | 校歌/标题类切片最高 0.547 |

**固化值：score-threshold = 0.60**（无关侧最大 0.547、相关侧最小 0.645，双侧余量 ≥0.05）。
效果：知识库外问题稳定触发话术A（used=0、fallback=NO_HIT、sources=[]），相关切片无一被误滤。

### 11.3 topK 校准（计划外必要调整）

联调发现默认 topK=5 时追问轮召回不足：改写式（如"新生具体的报到日期是什么时候？"）下含具体日期的切片
"报到时间：2025年9月10日7:30-17:30" 排名在 **7~9 位浮动**（改写措辞有轻微非确定性），导致用例②偶发答不出日期。
**固化值：top-k = 10**（夹取上限 20 不变）。复验：用例②稳定命中日期切片并正确引用 [n]。

### 11.4 estTokens 观察值（结构化日志）

| 场景 | estTokens |
|---|---|
| topK=2 首轮 | ≈193 |
| topK=10 首轮（历史 0~159 字符） | ≈331~404 |
| topK=20 首轮 | ≈794 |
| 话术A/B 轮 | ≈0（无主 LLM 调用） |

均在 LM Studio 上下文限制内，暂不需要基于 token 的历史裁剪（§10 第 3 项维持暂不做）。

### 11.5 联调用例执行结果（§8.3 ⓪~⑬）

| 用例 | 结果 | 备注 |
|---|---|---|
| ⓪ 零配置启动 | ✅ | 注释全部 rag.chat.* 启动，默认值（topK=10/阈值0.60）生效，问答可用 |
| gate 四项 | ✅ | 结论见 11.1 |
| ① 首轮提问 | ✅ | 新 UUID；reply 含材料+[n] 引用；sources index 连续 |
| ② 多轮追问 | ✅（校准后） | rewritten 含"时间/日期"；命中日期切片（topK=10 后稳定） |
| ③ 改写开关对照 | ✅ | query-rewrite=false 时 rewritten==raw；hits 排序变化已记录 |
| ④ 首轮知识库外 | ✅（校准后） | used=0、fallback=NO_HIT、话术A、sources=[] |
| ④b 带历史兜底 | ✅ | 记忆含本轮 user+话术A |
| ④c 兜底记忆连续性 | ✅ | history 含 ④ 的两条；改写读到"天气"上下文（rewritten="明天的天气预报"） |
| ④d LLM 空回复 | ✅（Mock 覆盖） | 真实 LM Studio 无法稳定构造空回复，由集成测试 Mock 断言话术C/sources=[]/fallback=EMPTY_REPLY |
| ⑤ topK 透传 | ✅ | topK=2 → used=2；topK=1000 → 夹取 20 |
| ⑥ 历史窗口 | ✅ | memory-max-messages=6 实测 history 条数=6（窗口裁剪生效） |
| ⑦ 清空与并发 | ✅（基础面） | 清空后 rewritten==raw、答案正常；并发中间态由条纹锁+集成测试覆盖 |
| ⑧ 非法参数 | ✅ | 100 字符 sessionId→400；3000 字符 message→400；sessionId=""→新会话 200 |
| ⑨ 停 LM Studio | ⚠️ 跳过实测 | 需中断共享的 LM Studio 服务；异常路径由 U/I Mock 用例覆盖（5xx + 回滚 + 无残留） |
| ⑩ score 分布 | ✅ | 见 11.2，阈值已固化 |
| ⑪ 中文长文件名 | ✅ | sources 中 source="jmu新生手册.txt" 完整显示（≤1024 字节） |
| ⑫ 预算耗尽 | ✅ | context-max-chars=80 实测：话术B（非话术A）、sources=[]、记忆含话术B；无命中查询仍正确返回话术A（语义不混淆） |
| ⑬ 改写过滤空 assistant | ✅（Mock 覆盖） | 由 U 用例断言改写 prompt 不含空 assistant 行 |

### 11.6 测试与覆盖率（§8.5 质量验收）

- `mvn verify` 一次性通过：**42 个测试全绿**（单元 15+9+2、集成 11、gate 4、启动 1）。
- JaCoCo LINE 覆盖率 **65.56%**（453 行中覆盖 297 行），远超 30% 卡点。
- **实施偏差修正**：原配置 includes 写成包名格式 `com.wuyunbin.rag.**`，JaCoCo 按 0 类分析导致卡点空转；
  已改为路径格式 `com/wuyunbin/rag/**`（与 excludes 口径一致），卡点真实生效。excludes 维持 RagApplication、MilvusRestStore 两项。

### 11.7 接口自测记录（成功 / 400 / 兜底 各一份）

**成功（POST /api/chat）：**

```
请求：{"message":"新生什么时候报到？"}
响应：{"sessionId":"03f98770-...","reply":"根据参考资料，新生报到时间是 2025年9月10日 [10]。",
      "sources":[{"index":1,"id":468982656319161980,"score":0.769,"source":"jmu新生手册.txt","text":"## 新生报到核查"}, ...]}
```

**400（非法参数）：**

```
请求：{"sessionId":"s×100","message":"测试"}     → 400 {"error":"sessionId 非法：仅允许字母、数字、下划线、中划线，长度 1~64"}
请求：{"message":"问×3000"}                      → 400 {"error":"message 超长（最多 2000 字符）"}
```

**兜底（知识库外 / 预算耗尽）：**

```
请求：{"message":"今天天气如何"}
响应：{"sessionId":"3c9fec3a-...","reply":"知识库中没有相关内容，请换个问法","sources":[]}

请求（context-max-chars=80 故意压预算）：{"message":"新生报到需要带什么材料？"}
响应：{"sessionId":"...","reply":"当前会话上下文较长，请清空会话或开启新会话再提问","sources":[]}
```

辅助端点：

```
GET  /api/chat/{sid}/history  → 4 条消息（两轮 user+assistant）；不存在 sid → []
DELETE /api/chat/{sid}        → {"cleared":true,...}；不存在 sid 幂等 200
```

### 11.8 环境与数据

- Milvus 3.0.1（docker compose，REST v2 端口 19530）：已 drop 旧集合并按新 schema（id/text/**source**/vector dim=1024）重建，
  重导入 `jmu新生手册.txt` **503 切片**，source 字段值正确（中文文件名）。
- LM Studio：google/gemma-4-e4b（生成）+ text-embedding-bge-m3（嵌入），base-url http://localhost:1234/v1。
- 交付代码清单与 §9 改动清单一致；另含 `dto/HistoryMessage.java`（§9 表未列，实施时新增，用于 history 端点）。
