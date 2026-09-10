# 计划：多轮问答召回正文详细日志（修订版）

## 摘要（Summary）
在既有「多轮问答」（RAG + 会话记忆）接口基础上，为**每次提问的文档召回**增加尽可能详细的日志：打印被召回 chunk 的 `id`、`score`、`source` 与正文 text 内容，并附检索耗时。日志改动局部、低风险，不破坏既有业务逻辑。

- 日志级别：`INFO`（延续既有 `[chat]` 风格，新增独立前缀 `[chat-retrieval]` 便于 grep）
- 控制开关：新增配置 `rag.chat.log-retrieval`，默认 `true`（开发环境默认开，生产建议覆盖为 `false`）
- 记录范围：**过滤前（原始 topK）+ 过滤后（实际入 Prompt）都记**，raw 与 used 行均含正文（截断后），便于对照「召回 vs 实际使用」
- 前置动作：将当前工作区被回退的功能文件恢复为 commit `05e4d6b`（完整可编译基线）

---

## 当前状态（Current State Analysis）

### 工作区不一致问题（必须先处理）
`git status` 显示除 `ChatService.java` 外的绝大多数功能文件被回退到「单轮问答」旧版本，与仍保留完整多轮版的 `ChatService.java` 互相矛盾，**当前无法编译**：

- 当前 [RagProperties.java](file:///d:/A/rag-xmut/rag/src/main/java/com/wuyunbin/rag/config/RagProperties.java) 只剩 `milvus`/`ingest`，无 `Chat` record；而 [ChatService.java](file:///d:/A/rag-xmut/rag/src/main/java/com/wuyunbin/rag/service/ChatService.java#L130-L145) 大量调用 `props.chat().xxx()`
- [ChatMemoryConfig.java](file:///d:/A/rag-xmut/rag/src/main/java/com/wuyunbin/rag/config/ChatMemoryConfig.java) 已空；[ChatController.java](file:///d:/A/rag-xmut/rag/src/main/java/com/wuyunbin/rag/controller/ChatController.java) 为旧的 `chatService.chat(message)` 单参调用
- [multi-turn-qa-plan.md](file:///d:/A/plan/multi-turn-qa-plan.md) 为 0 行空文件

**处置（已确认）**：实施开始时用 `git restore` 恢复到 commit `05e4d6b` 得到完整可编译基线，日志改动叠加其上。

### 日志现状
检索流程与现有日志集中在 [ChatService.doAsk](file:///d:/A/rag-xmut/rag/src/main/java/com/wuyunbin/rag/service/ChatService.java#L134-L197)：

1. `embeddingModel.embed(rewritten)` → 查询向量
2. `milvusRestStore.search(queryVector, topK)` → 原始 `hits`（阈值过滤前，含 `id`/`distance`/`text`/`source`）
3. `threshold = props.chat().scoreThreshold()`，`filtered = hits` 中 `score>=threshold` 者
4. 兜底 A/B 时 `sources` 为 `List.of()`；否则 `buildReferences(...)` → `refs.sources()`（实际入 Prompt 的切片）
5. 末尾 [logTurn](file:///d:/A/rag-xmut/rag/src/main/java/com/wuyunbin/rag/service/ChatService.java#L349-L361) 打一行摘要——只含 `scores=[id:score,...]`，**不含正文，也不含耗时**

→ 缺失点：被召回 chunk 的正文内容与检索耗时未打印。所需字段已在内存中，改动局部、低风险。

### 依赖与约定（来自项目记忆）
- 检索字段 `id`/`distance`(COSINE 相似度=score)/`text`/`source` 均已在完整版 [MilvusRestStore.search](file:///d:/A/rag-xmut/rag/src/main/java/com/wuyunbin/rag/service/MilvusRestStore.java#L127-L149) 返回
- 可复用辅助方法：`score(map)`、`textOf(map)`、`idOf(map)`、`sourceOf(map)`、`truncate(str,maxChars)`
- `RagProperties.Chat` record 全字段带 `@DefaultValue`，并有紧凑构造器兜底（恢复后存在）
- 配置命名习惯：`rag.chat.*` 用 kebab-case（`top-k`、`score-threshold`、`query-rewrite`、`chunk-max-chars`），Java 字段用 camelCase

---

## 拟修改（Proposed Changes）

### 步骤 0：恢复完整版基线
**文件**：整个 `d:\A\rag-xmut\rag` 工作区
**How**：用**显式指定源 commit** 的 restore，避免与「当前 HEAD」混淆：
```powershell
git -C d:\A\rag-xmut\rag restore --source=05e4d6b -- .
```
> 说明：当前实施基线为 commit `05e4d6b`，因此显式指定 `--source`，避免依赖当前 HEAD，计划以后也不会因 HEAD 变更而失效。`git restore .`（不带 `--source`）默认恢复的是当前 HEAD，而非"指定的 05e4d6b"，故命令明确写 `--source=05e4d6b`，使计划与命令严格一致。
**Why**：日志改动必须建在可编译的完整功能上；当前是半回退状态，直接叠改会出错。
**验证**：`mvn -q -f d:\A\rag-xmut\rag\pom.xml test-compile` 通过。

---

### 1) 新增配置项 `rag.chat.log-retrieval`
**文件**：[RagProperties.java](file:///d:/A/rag-xmut/rag/src/main/java/com/wuyunbin/rag/config/RagProperties.java)
**What**：在 `Chat` record 末尾追加字段；同步紧凑构造器默认值。
**How**：
```java
public record Chat(
        @DefaultValue("10") int topK,
        @DefaultValue("0.60") double scoreThreshold,
        @DefaultValue("20") int memoryMaxMessages,
        @DefaultValue("true") boolean queryRewrite,
        @DefaultValue("600") int chunkMaxChars,
        @DefaultValue("4000") int contextMaxChars,
        @DefaultValue("1024") int maxTokens,
        @DefaultValue("true") boolean logRetrieval,
        @DefaultValue("10") int logRetrievalMaxRaw) {
}
```
```java
public RagProperties {
    if (chat == null) {
        chat = new Chat(10, 0.60, 20, true, 600, 4000, 1024, true, 10);
    }
}
```
**Why**：开关控制详细正文日志，可随时关闭。命名沿用 kebab-case 现有习惯（`log-retrieval`、`log-retrieval-max-raw`）；曾考虑 `retrievalLogging`/`logRetrievedChunks`，因现有 `query-rewrite`、`chunk-max-chars` 均为 kebab 功能名，`log-retrieval` 与现有后 7 个参数风格最一致，故保留。若未来加 `logPrompt/logRewrite` 等，另行命名即可。
**验证**：`@DefaultValue("true")` 保证零配置默认开。

**文件**：[application.properties](file:///d:/A/rag-xmut/rag/src/main/resources/application.properties)
**How**：在「Multi-turn QA」注释块追加：
```properties
# Whether to log each retrieved chunk (id/score/source/text) and retrieval timing at INFO;
# 开发环境默认 true；生产部署时建议通过配置覆盖为 false（正文可能含敏感信息，仅联调/排查时开启）
rag.chat.log-retrieval=true
# Max number of raw (pre-filter) hits to print per question; extra hits are summarized as "raw 省略 K 条"
rag.chat.log-retrieval-max-raw=10
```

---

### 2) ChatService 打印召回正文 + 耗时
**文件**：[ChatService.java](file:///d:/A/rag-xmut/rag/src/main/java/com/wuyunbin/rag/service/ChatService.java)

**2a) 计时**：在 `doAsk` 检索段用 `retrievalStart` 包住**整个检索阶段**（含未来可能插入的 rewrite/validate/metrics 逻辑），并分别统计 embed 与 search：
```java
long retrievalStart = System.nanoTime();
long embedStart = System.nanoTime();
float[] queryVector = embeddingModel.embed(rewritten);
long embedCostMs = (System.nanoTime() - embedStart) / 1_000_000L;
long searchStart = System.nanoTime();
List<Map<String, Object>> hits = milvusRestStore.search(queryVector, topK);
long searchCostMs = (System.nanoTime() - searchStart) / 1_000_000L;
long retrievalTotalMs = (System.nanoTime() - retrievalStart) / 1_000_000L;
```
**Why**：`Total` 覆盖整个检索阶段，中间插入逻辑时总耗时计算无需改动（`embed+search` 简单相加会在未来失真）。`Embedding Xms / Search Yms / Total Zms` 一眼定位瓶颈。

**2b) 统一调用位置（评审②修复）**：`doAsk` 现有两处 `logTurn`（A/B 分支共用底部第 194 行；正常分支第 189 行）。**在两处都采用唯一确定顺序**：
```java
logRetrieval(sessionId, rewritten, threshold, hits, sources, embedCostMs, searchCostMs, retrievalTotalMs);
logTurn(...);   // 既有摘要行，参数不变
return new ChatAnswer(sessionId, reply, sources);
```
> 即：**每一处 exit 都是 `logRetrieval → logTurn → return`**，顺序在计划与实现中完全一致（`[chat-retrieval]` 先、`[chat]` 后，符合阅读习惯）。
> 注：`embedCostMs/searchCostMs/retrievalTotalMs/hits/threshold/sources` 均在分支前取得或在作用域内。

**2c) 新增方法 `logRetrieval`**：
```java
/** 改写后 Query 日志打印的最大长度（与 chunk 语义无关，独立常量） */
private static final int MAX_QUERY_LOG_LENGTH = 200;

/** source 归一化：null/blank → "unknown"（raw 与 used 单一口径） */
private String normalizeSource(String src) {
    return (src != null && !src.isBlank()) ? src : "unknown";
}

/**
 * 详细召回日志（受 rag.chat.log-retrieval 控制，INFO 级）。
 * 过滤前 topK 每行打 id/score/source/passed 与正文（text 按 chunkMaxChars 截断）；
 * 过滤后实际入 Prompt 的切片再打一遍（used[..]），便于对照「召回 vs 实际使用」。
 * rewritten 过长时按 MAX_QUERY_LOG_LENGTH 截断，避免长查询刷屏。
 */
private void logRetrieval(String sessionId, String rewritten, double threshold,
                          List<Map<String, Object>> hits, List<ChatAnswer.SourceItem> sources,
                          long embedCostMs, long searchCostMs, long retrievalTotalMs) {
    if (!props.chat().logRetrieval()) {
        return;
    }
    int chunkMaxChars = props.chat().chunkMaxChars();
    int maxRaw = props.chat().logRetrievalMaxRaw();
    log.info("[chat-retrieval] sessionId={}, rewritten={}, threshold={}, rawHits={}, usedRefs={}, "
                    + "embedCostMs={}, searchCostMs={}, retrievalTotalMs={}",
            sessionId, truncate(rewritten, MAX_QUERY_LOG_LENGTH), threshold,
            hits == null ? 0 : hits.size(), sources == null ? 0 : sources.size(),
            embedCostMs, searchCostMs, retrievalTotalMs);

    if (hits != null && !hits.isEmpty()) {
        int shown = 0;
        for (Map<String, Object> h : hits) {
            if (shown >= maxRaw) {                    // raw 条数上限可配置
                break;
            }
            double score = score(h);                 // 评审⑥：局部变量复用，避免重复取值
            log.info("[chat-retrieval] sessionId={}, raw[{}] id={}, score={}, source={}, passedThreshold={}, text={}",
                    sessionId, shown + 1, idOf(h), score, sourceOf(h), score >= threshold,
                    truncate(textOf(h), chunkMaxChars));
            shown++;
        }
        if (hits.size() > shown) {                   // raw 超上限时给出省略提示
            log.info("[chat-retrieval] sessionId={}, raw 省略 {} 条",
                    sessionId, hits.size() - shown);
        }
    }
    if (sources != null) {
        for (ChatAnswer.SourceItem s : sources) {
            // 防御式截断：SourceItem.text 已在 buildReferences 里 truncate 过，
            // 此处仍再 truncate 一次，不依赖其内部实现细节，重复截断无副作用。
            // source 统一走 normalizeSource，与 raw 的 sourceOf 同口径。
            log.info("[chat-retrieval] sessionId={}, used[{}] id={}, score={}, source={}, text={}",
                    sessionId, s.index(), s.id(), s.score(),
                    normalizeSource(s.source()),
                    truncate(s.text(), chunkMaxChars));
        }
    }
}
```
- `raw[i]` 与 `used[i]` 都带正文（text 均按 chunkMaxChars 截断）：raw 反映「召回了什么」，used 反映「实际入 Prompt 用到了什么」，便于对照；重复由 `log-retrieval-max-raw` 控制总量
- 评审⑤：新增 `normalizeSource(String)`（null/blank → `"unknown"`）作为唯一口径；现有 `sourceOf(h)` 改为委托它，`used` 行直接调用它
```java
// sourceOf 委托 normalizeSource，单一口径
private String sourceOf(Map<String, Object> hit) {
    Object s = hit.get("source");
    return normalizeSource(s instanceof String str ? str : null);
}
```
- 前缀 `[chat-retrieval]` 独立标签，便于 grep 联调，不与既有 `[chat]` 摘要行混淆

---

### 3) 同步测试
**文件**：[RagPropertiesTest.java](file:///d:/A/rag-xmut/rag/src/test/java/com/wuyunbin/rag/config/RagPropertiesTest.java)（恢复后的完整版）
**How**：
- 零配置默认：断言 `props.chat().logRetrieval() == true`、`props.chat().logRetrievalMaxRaw() == 10`
- 显式配置：绑定 `rag.chat.log-retrieval=false` 时断言 `false`；绑定 `rag.chat.log-retrieval-max-raw=20` 时断言 `20`
**Why**：保持既有「配置默认值与绑定」测试口径，覆盖新字段。

---

## 假设与决策（Assumptions & Decisions）
- **基线**：日志基于恢复后的 commit `05e4d6b` 完整版叠加；不重构检索/流程，只在既有 `logTurn` 处配套追加。
- **开关**：`rag.chat.log-retrieval` 默认 `true`（INFO 直接可读）；**开发环境默认 `true`，生产部署时建议通过配置覆盖为 `false`**——正文可能含身份证/手机号/邮箱/API Key 等敏感信息，INFO 输出正文存在数据泄漏面。
- **记录**：过滤前（raw：id/score/source/passed/text）+ 过滤后（used：id/score/source/text）都记；正文 raw 与 used 各打一次（便于对照「召回 vs 实际使用」），总量由 `log-retrieval-max-raw` 控制；两条正文均按 `chunk-max-chars` 且二次 `truncate` 防御。
- **raw 上限**：`rag.chat.log-retrieval-max-raw` 可配置（默认 10），超限仅打省略行（避免未来 topK 增大日志爆炸）。
- **耗时**：`embedCostMs`、`searchCostMs` 分别统计，`retrievalTotalMs` 用 `retrievalStart` 包住整个检索阶段（非简单相加），打在 `[chat-retrieval]` 摘要行。
- **rewritten**：日志中按独立常量 `MAX_QUERY_LOG_LENGTH=200` 截断（与 chunk 语义解耦，chunkMaxChars 增大时不影响 Query 日志长度）。
- **source 空值**：统一走 `normalizeSource(String)`（null/blank → `"unknown"`）；`sourceOf(h)` 委托它，单一口径。
- **调用顺序**：每处 exit 固定 `logRetrieval → logTurn → return`。
- **命名**：`log-retrieval`、`log-retrieval-max-raw` 与现有 kebab 配置一致。
- **不引入**：不改成 JSON/结构化日志、不新增日志配置文件（与既有 `[chat]` 简单写法一致）。

---

## 验证（Verification）
1. **恢复后编译**：`git restore --source=05e4d6b -- .` 后 `mvn -q -f d:\A\rag-xmut\rag\pom.xml test-compile` 通过（基线可编译）。
2. **单元测试**：`mvn -f d:\A\rag-xmut\rag\pom.xml test` 全绿（含新增 `RagPropertiesTest`），JaCoCo 门槛不受影响。
3. **开关**：`rag.chat.log-retrieval=false` 时调用 `ask`，日志不再出现 `[chat-retrieval]` 行，既有 `[chat]` 摘要行正常。
4. **边界（易遗漏）**：`rag.chat.score-threshold=1.0` 时（全部不过阈值）调用 `ask`，确认：
   - 走兜底 A（`fallback=NO_HIT`），`usedRefs=0`；
   - `[chat-retrieval]` 摘要行仍打印（rawHits=N、usedRefs=0）；
   - `raw[i]` 行仍打印、`passedThreshold=false`；
   - `used[..]` 循环不执行，**无 NPE**。
5. **边界（maxRaw=0）**：`rag.chat.log-retrieval-max-raw=0` 时调用 `ask`，确认：
   - raw 全部省略（不打印任何 `raw[i]` 行，只打 `raw 省略 N 条`），**无异常**；
   - `used[i]` 行正常打印（正文不受影响）。
6. **联调（可选，需 LM Studio 与 Milvus）：** 真实 `ask` 一次，校验：
   - `[chat-retrieval] ... rawHits=N, usedRefs=M, embedCostMs=.., searchCostMs=.., retrievalTotalMs=..` 摘要存在；
   - `raw[i]` 行含 id/score/source/passedThreshold/text，text 长度 ≤ chunkMaxChars；
   - `used[i]` 行含 id/score/source/text，text 长度 ≤ chunkMaxChars，source 不为 null；
   - used 编号与 `[chat]` 摘要 `used=M` 及答案中 `[i]` 引用一致；
   - topK 场景下 `raw 省略 K 条` 按预期出现。
7. **生产实践**：开发环境默认 `true`；生产部署时通过配置覆盖为 `false`，避免生产记录敏感正文。

---

## 待实施步骤清单
1. `git -C d:\A\rag-xmut\rag restore --source=05e4d6b -- .`；`test-compile` 验证基线
2. `RagProperties.Chat` 增加 `@DefaultValue("true") boolean logRetrieval` + `@DefaultValue("10") int logRetrievalMaxRaw`，同步紧凑构造器默认值
3. `application.properties` 追加 `rag.chat.log-retrieval=true`、`rag.chat.log-retrieval-max-raw=10` 及注释（含生产默认关闭建议）
4. `ChatService.doAsk` 加 retrieval/embed/search 计时；新增 `MAX_QUERY_LOG_LENGTH=200`、`normalizeSource(...)`（`sourceOf` 委托）、`logRetrieval(...)`（rewritten 截断、raw 可配置上限、used source 归一化、used text 二次截断）；两处 exit 统一 `logRetrieval → logTurn → return`
5. `RagPropertiesTest` 补 `logRetrieval` 与 `logRetrievalMaxRaw` 的默认及显式绑定断言
6. `mvn test` 全绿；开关 `false` 时 `[chat-retrieval]` 不出现；`score-threshold=1.0`、`log-retrieval-max-raw=0` 边界无异常（验证 3/4/5）

---

## 计划完成情况（实施后记录）

**状态**：✅ 全部实施并验证通过（45 个测试全绿、JaCoCo 覆盖率门槛通过、`mvn verify` BUILD SUCCESS）。下述步骤 0–6 均已按计划完成，并含 3 项计划外调整（均经确认）。

### 实际改动（相对基线 commit `05e4d6b`，共 6 个文件）
| 文件 | 改动 |
|---|---|
| [RagProperties.java](../../rag-xmut/rag/src/main/java/com/wuyunbin/rag/config/RagProperties.java) | `Chat` record 新增 `logRetrieval`（默认 `true`）+ `logRetrievalMaxRaw`（默认 `10`），紧凑构造器兜底同步 |
| [application.properties](../../rag-xmut/rag/src/main/resources/application.properties) | 追加 `rag.chat.log-retrieval=true`、`rag.chat.log-retrieval-max-raw=10` 及注释（含生产建议 false） |
| [ChatService.java](../../rag-xmut/rag/src/main/java/com/wuyunbin/rag/service/ChatService.java) | `doAsk` 检索段计时（embed/search/total，`retrievalStart` 包住整个检索阶段）；新增 `MAX_QUERY_LOG_LENGTH=200`、`normalizeSource(...)`（`sourceOf` 委托）、`logRetrieval(...)`；两处 exit 均 `logRetrieval → logTurn → return` |
| [MilvusRestStore.java](../../rag-xmut/rag/src/main/java/com/wuyunbin/rag/service/MilvusRestStore.java) | `search()` 返回前按相似度（distance/COSINE score）**显式降序排序**（计划外，见下） |
| [RagPropertiesTest.java](../../rag-xmut/rag/src/test/java/com/wuyunbin/rag/config/RagPropertiesTest.java) | 补 `logRetrieval`/`logRetrievalMaxRaw` 默认值与显式绑定断言 |
| [ChatServiceTest.java](../../rag-xmut/rag/src/test/java/com/wuyunbin/rag/service/ChatServiceTest.java) | 新增 U16（threshold=1.0）、U17（maxRaw=0）、U18（开关关闭）三个边界测试（计划外，见下） |

### 计划外调整（实施中经确认追加）
1. **raw 行补正文 `text=`**：首版按评审建议「正文只在 used 打一次」，真实联调时发现 raw 行只有元数据、无具体文本，与「打印被召回的文档内容」的原始需求不符 → 改为 raw 与 used 行均带正文（各按 `chunkMaxChars` 截断），便于对照「召回 vs 实际使用」，重复总量由 `log-retrieval-max-raw` 控制。
2. **MilvusRestStore 显式降序排序**：按用户要求「相似度按照降序排序」，在 `search()` 返回前显式 `sort(reversed)`，保证 raw[i] 日志、过滤后 sources、回答引用编号 `[1]`=最相似，不依赖 Milvus 默认行为。
3. **修正计划错误**：`ChatAnswer.SourceItem` 访问器为 `id()`（非计划中写的 `chunkId()`），实现时已改用 `s.id()` 并同步修正本计划 §2c 代码。

### 验证结果
- `mvn verify`：**45 个测试全绿**（42 基线 + U16/U17/U18），JaCoCo LINE 覆盖率门槛（≥30%）通过，BUILD SUCCESS。
- 边界场景均无 NPE：
  - `score-threshold=1.0`：走兜底 A，`usedRefs=0`，摘要/raw 行仍打印、`passedThreshold=false`（U16）；
  - `log-retrieval-max-raw=0`：raw 全省略只打「raw 省略 N 条」、used 正常（U17）；
  - `log-retrieval=false`：无任何 `[chat-retrieval]` 行，业务不受影响（U18）。
- 测试日志可直接看到降序的 `raw[1] score=0.9 → raw[2] score=0.8` 及截断正文。

### 遗留 / 待办
- **真实联调（可选）**：LM Studio + Milvus 运行时 `mvn spring-boot:run` 后发一次提问，核对 `[chat-retrieval]` 摘要（rawHits/usedRefs/embedCostMs/searchCostMs/retrievalTotalMs）、raw/used 正文、`raw 省略 K 条` 及与回答引用编号一致性。
- **生产实践**：开发环境默认 `true`；生产部署时通过配置覆盖为 `false`，避免记录敏感正文（身份证/手机号/邮箱/API Key 等）。