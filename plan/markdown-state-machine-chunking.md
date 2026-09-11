# 基于状态机的 Markdown 文档语义分块（整体替换清洗+切片管线）

> 项目：d:\A\rag-xmut（Spring Boot 4.1.1 + Spring AI 2.0 + Milvus 3.0.1 REST v2 + LM Studio）
> 依据：`d:\A\rag-xmut\plan\StateMachine.md`
> 状态：**已实现（2026-09-11）**，见文末「§实施进度记录」
> 已确认决策：①整体替换现有 `DocumentCleaner` + `semanticSplit`；②块即入库（正文块+表格块都用普通 text chunk，曲调剔除，每块附标题链元数据）；③纳入 TOC 状态。

> 本轮新增决策：④以「两个标题之间为一块整体」切分（不再按字节中途切分，仅超硬性上限兜底）；⑤Milvus `text` 字段上限提升至 16384 以容纳整块标题区块。

## 摘要

新增一个**单遍、逐行驱动的有限状态机（FSM）解析器** `MarkdownStateMachineParser`，读取文档原文后按标题层级切分为语义块 `List<Chunk>`，一次遍历同时完成「目录过滤 / 表格识别 / 曲调剔除 / 正文按标题分块」——取代现有 `DocumentCleaner`（R2/R3/R1 行清洗）与 `semanticSplit`（余弦语义切片）。每个 `Chunk` 带 `type`（TEXT/TABLE）与 `titleChain`（标题链元数据）两个元数据，贴近 Milvus 入库与溯源需求。

状态集：`TITLE`、`TOC`、`TABLE`、`MUSIC_SCORE`、`TEXT`（相对 StateMachine.md 新增 `TOC`，用户已确认纳入）。

优先序：`TABLE` > `TOC` > `MUSIC_SCORE` > `TITLE` > `TEXT`（表格/目录优先判定，曲调不误吞表格）。

## 当前状态分析

- 现有清洗：`DocumentCleaner.clean()` 三阶段 `R2→R3→R1`，返回 `CleanedDocument(text, tables)`，其中正文再交给 `semanticSplit` 按句余弦合并——**两个阶段分别遍历文本，且 `semanticSplit` 会对中间句子做一次 embed**。
- 已知缺陷（上一轮单测 4 失败）：末尾换行重复、孤立 `| 1 | 2 |` 数字管道行被 R1 误删为曲调、`simulateEmbedFailure` 检查位于 `semanticSplit` 之后挡不住内嵌 embed。
- 调用面：`DocumentCleaner`、`semanticSplit`、`cosineSimilarity` 仅被 `DocumentIngestService` 及对应测试引用（grep 确认，无其他调用方）。
- Milvus schema（`MilvusRestStore.createCollection`）：`id`(Int64 auto)、`text`(VarChar 2048)、`source`(VarChar 1024)、`vector`(FloatVector)。ingest 每次 `tryDrop()` → `ensureCollection()` 重建，**修改 schema（新增 `title` 字段）无迁移负担**。
- 配置：`rag.clean.enabled`、`rag.clean.table-max-bytes`、`rag.ingest.clear-before-ingest`、`rag.test.simulate-embed-failure` 已存在。

## 状态机设计（决策完整）

解析器逐行读取，维护一个 `MarkdownState` 枚举与「当前标题栈」。

### 1. 状态与判定优先级

| 优先级 | 状态 | 进入条件 | 退出条件 |
|---|---|---|---|
| 1 | **TABLE** | 当前行 `isTableRow` 且下一行为对齐分隔行；或当前行是对齐分隔行之前的表头 | 遇标题行；遇非表行（列数 ≠ 表头列数 / 空行 / 普通行）→ 立即切块、回 TEXT/TITLE |
| 2 | **TOC** | 标题行解析后其文本匹配 `目录`/`CONTENTS`（大小写不敏感） | 遇下一个 `#` 或 `##` 章节标题（该标题触发新 TITLE）；否则兜底删连续条目行后回 TEXT |
| 3 | **MUSIC_SCORE** | 触发行：调号 `^\s*\d\s*=\s*\S`（如 `1=F`）、拍号 `^\s*\d\s*/\s*\d`（如 `2/4`）；或当前标题含 `校歌` | 遇下一标题行 → 结束区域回 TITLE；其余情况一直持续到文件尾 |
| 4 | **TITLE** | 行以 `#` 开头（`#{1,6}\s+\S`） | 立即：flush 上一 TEXT 块 → 更新标题栈 → 进入 TEXT（标题文本不入正文，仅作 `titleChain` 元数据） |
| 5 | **TEXT** | 非上述任何情形 | 遇 TITLE flush；文本缓冲达到字节上限时提前 flush |

### 2. 各状态动作

- **TITLE（含章节首行）**
  - 解析 `#` 个数 → 层级 `level`；内容 `text`。
  - 若 `text` 匹配 `目录`/`CONTENTS` → 进入 **TOC**，不 flush。
  - 否则：flush 当前累积的 TEXT 缓冲（emit TEXT chunk）；将当前标题压入/截断标题栈（同级或更高级弹出，更深级压入）；标题文本不入正文。
- **TOC**
  - 丢弃后续行，直到遇 `^#{1,2}\s+\S` 章节标题 → 按新标题进入 TITLE（防多级目录吞正文，保留既有 `^#{1,2}` 语义）。
  - 若到文件尾都无章节标题 → 兜底：删除途中匹配条目形态 `^.+?[\s.·]{1,}\d{1,3}$` 的行，遇首个非条目非空行即止（该行保留，回 TEXT）。
  - 空行归属 TOC 删除区。
- **TABLE**
  - 进入时缓存：表头行 + 对齐分隔行；记录 `headerCols = 表头按 \| 分割的非空片段数`。
  - 持续吸收**列数 == headerCols** 的数据行。
  - 退出（标题 / 空行 / 列数不符 / 遇新"表头+分隔行"）→ flush 该表块为一个 **TABLE chunk**；若块 `getBytes(UTF-8) > rag.clean.table-max-bytes`，按数据行二次切分为多个子块，每子块仍含表头+对齐分隔行（复用现 `addTableBlockSplit` 逻辑迁移）。
- **MUSIC_SCORE**（曲调识别，防误判核心）
  - **区域内**（已触发/标题含校歌）：丢弃含 ≥1 数字的行，以及 CJK 字符数 < 4 的注解行（覆盖 `(庄严)`、`2/4(庄严)`）。文字歌词（CJK ≥ 4 且无数字干扰）保留。
  - **全局**（区域外）：仅在同时满足以下条件时丢弃——
    1. 含 ≥1 数字；
    2. 全部字符 ∈ `{0-9、.、空格、\|}`；
    3. 含 `|` **或** 独立延音线（前后都为空格的 `-`）。
    该项刻意排除：无符号的纯数字/电话号码 `0592-6181301`、`138 1234 5678`、`地址:…1号`（含 CJK 或 `-` 非独立或非白名单）。
  - **TABLE 优先**：任何构成合法表格（表头+对齐分隔行）的管道行一律入 TABLE，绝不判为曲调（解决现 `dataRowsWithoutHeaderNotTable` 遗留：孤立管道行不构成表格，按全局规则可能被判为曲调并按上述白名单丢弃——这是相对旧 `C9b` 的**有意行为收紧**，见「决策」）。
- **TEXT**
  - 累积到缓冲；写入时时 `titleChain` = 当前标题栈拼接（`第1章 > 1.1节`）。
  - 缓冲字节限制：`text.getBytes(UTF-8) > rag.clean.chunkMaxBytes`（默认 1500，与 table 对齐可配 `rag.clean.*`）；超限提前 flush 出多个 TEXT chunk，共享同一 `titleChain`。
  - 空行归入当前 TEXT 缓冲（连续多个空行归并为一个换行）。

### 3. 输出模型

```java
public record Chunk(int index, ChunkType type, String titleChain, String text) {
    public enum ChunkType { TEXT, TABLE }
}
```

- `index` 从 1 递增，作块编号；`type` 供溯源区分正文/表格；`titleChain` 记录「来源章节路径」；`text` 为待向量化+入库正文。
- 曲调不产出 chunk（被剔除）。

## 变更清单（文件级）

### 1. 新增 `rag/src/main/java/com/wuyunbin/rag/service/MarkdownStateMachineParser.java`
- 承载上述状态机；`parse(String rawText) → List<Chunk>`。
- 纯函数化辅助方法 `static`（`isTableRow`、`isTableAlignRow`、`isScoreTrigger`、`countCjk`、`columnCount`、`isTocEntry` 等），便于单测。
- 前缀：统一换行 `replaceAll("\\r\\n?", "\n")`，规整末尾换行（修复旧 `noTocHeadingUnchanged` 缺陷）。
- 汇总日志 `[markdown-state] chunks={}, tocLines={}, tables={}, scoreLines={}` 按 source 区分（沿用现有日志风格）。

### 2. 删除 `DocumentCleaner.java`（被整体替换，其 R1/R2/R3 逻辑并入状态机）

### 3. 改造 `DocumentIngestService.java`
- 构造注入 `MarkdownStateMachineParser`，替换 `DocumentCleaner` 依赖。
- `ingest()`：将「`cleaner.clean()` + `semanticSplit`」改为「`parser.parse(text)` → 全量 embed（每 chunk 一次，无中间句子 embed）→ insert」。
- **`simulateEmbedFailure` 检查提前**到任何 embed/parse 之前（修复旧时机缺陷）：`if (props.test().simulateEmbedFailure()) throw ...` 置于解析前。
- `clean.enabled=false` 分支：复用原 Document 不重建，改为 `parser.bypass(text)` 将整篇原文作为**单个** TEXT chunk 输出（等价现兼容路径，不调清理）。
- `semanticSplit`、`cosineSimilarity` 方法移除；`partition` 保留（用于 embed 分批）。
- **metadata/schema**：insert 行新增 `title = chunk.titleChain()`。

### 4. 扩展 `MilvusRestStore.java`
- `createCollection()` 新增 `title` 字段：`VarChar max_length=1024`（UTF-8 字节安全，截断策略同 source：按 256 字符粗截）。
- `insert()` 不变（receive rows map，天然支持新字段）。
- `search()` 的 `outputFields` 增加 `"title"`；返回 map 增加 `title`（read row），供溯源/日志。

### 5. 配置 `RagProperties` + `application.properties`
- 复用现有 `rag.clean.enabled`（总开关）与 `rag.clean.table-max-bytes`（表格/正文块字节上限）。不改字段，或仅将 `Clean` record 注释语义更新为「正文块/表格块字节上限」。
- 不新增配置项（默认值 1500 沿用）。

### 6. 测试
- **新增 `MarkdownStateMachineParserTest`**：按状态覆盖
  - TITLE：一级/多级标题分块、标题栈正确、相邻标题连续时前块 flush。
  - TOC：`## 目录` + 条目 + `#` 章节；多级目录 `## 第一章` 停止；无章节兜底 + 装饰行；误判（无目录标题）不变。
  - TABLE：单一表整体一块、两表连续切两块、列数不符正文行不被吞、超长按字节二次切分且每块含表头、纯数字表头不被判曲调。
  - MUSIC_SCORE：校歌区块（调号/拍号/CJK<4 注解删、CJK≥4 歌词留）；全局白名单；电话号码/地址/日期不误删；孤儿 `| 1 | 2 |` 行为符合「决策」。
  - 元数据：`titleChain`/`index`/`type` 正确；`clean.enabled=false` 单块整篇输出。
- **删除 `DocumentCleanerTest`**（整体替换，用例并入 parser 测试）。
- **改造 `DocumentIngestServiceTest` / `DocumentIngestServiceLogicTest`**：改用 `parser` mock；`simulateEmbedFailure` 断言改为「解析/embed 前即抛，`milvus` 未调用」；移除 `semanticSplit`/`cosineSimilarity` 相关用例；补插入行含 `title` 断言；保留 C13（clean.enabled=false 不调 parser 清理）、C14（clearBeforeIngest=false 不 drop）。
- **`RagPropertiesTest`**：表字段断言微调（若仅注释不变则无改动）。

### 7. 文档
- 更新 `d:\A\rag-xmut\plan\document-cleaning-plan.md`：在「§10 实施进度记录」追加本方案为 v5（状态机整体替换），标注旧 §10.2 的 4 个失败由新实现一并消解。

## 假设与决策

1. **整体替换**：`DocumentCleaner` 与 `semanticSplit` 删除，功能并入单遍状态机。副作用：`semanticSplit` 的余弦语义合并不再生效，分块转为「按标题边界 + 字节上限」。已确认。
2. **块即入库**：正文/表格均以普通 text chunk 入库；`title` 存为独立字段（`titleChain`），**不**拼入嵌入文本，避免标题污染向量；曲调块不产出。已确认。
3. **新增 `title` schema 字段**：因 ingest 每次 `tryDrop` 重建集合，无迁移成本；需在联调时重新入库。
4. **孤儿管道行收紧**：`| 1 | 2 |`（无表头/分隔行）按全局曲调/文本规则处理，不再是「保为正文」——`C9b` 语义随整体替换调整，属有意变更。
5. **曲调从简**：不维护长期全局曲调状态，仅「区域（校歌标题触发或调号/拍号命中）」+「全局白名单独立行」两类，避免过度复杂化。
6. **`clean.enabled=false`**：整篇原文作为单个 TEXT chunk（不做任何清洗/分块），保持兼容回退路径。
7. **标题栈深度**：`titleChain` 仅记录出现过的标题链条，最多保留当前层级栈，非全量冗余。

## 验证步骤

1. `mvn test-compile`：确认删除 `DocumentCleaner` 后无残留引用。
2. `mvn test -Dtest=MarkdownStateMachineParserTest,DocumentIngestServiceTest,...,RagPropertiesTest`：单测全绿，且旧 4 失败消失。
3. `mvn test`（全量）通过；JaCoCo 复核：全局 LINE≥30%，`MarkdownStateMachineParser` LINE≥70%（目标≥85%）。
4. 联调（真实 Milvus + LM Studio）：`mvn spring-boot:run` → 传 `d:\A\jmu新生手册.txt` → `GET /api/knowledge/list` 核对：切片按标题分块、无曲调区、目录块已删、表格整块在、`title` 字段有值、`[markdown-state]` 日志按 source 输出。
5. 检索命中：问 `收费标准` / `校歌歌词` 命中对应 TABLE/TEXT 块，`title` 溯源可用。
6. 失败路径：`rag.test.simulate-embed-failure=true` 上传 → 报错且 Milvus 旧数据未清空；确认开关在 parse/embed 之前生效。
7. `tryDrop` 幂等复查：空集合首跑不抛异常（确认项 A，沿用 §4.4 计划）。

## 风险与备选

- **语义分块行为变更**：从「余弦语义合并」切到「按标题+字节」，长段落连续性可能变化；需联调核对有用性，必要时调高块字节上限或按标题层级补充合并。
- **曲调白名单较严**：若真实档案出现未覆盖的曲调形态（如含连字符的数字旋律），联调按具体触发规则再校准正则。
- **schema 加字段**：旧集合需重新入库（已在 ingest drop-reload 覆盖）。

---

## §实施进度记录（2026-09-11）

### §1 已完成

1. **新增 `MarkdownStateMachineParser`**（`rag/src/main/java/.../service/MarkdownStateMachineParser.java`）
   - FSM 状态集 `TITLE/TOC/TABLE/MUSIC_SCORE/TEXT`，优先序 `TABLE > TOC > MUSIC_SCORE > TITLE > TEXT`。
   - 单遍逐行解析，输出 `List<Chunk>`（`index/type/titleChain/text`），`parse()` 按标题栈切分。
   - 以两个标题之间为一块整体：正文 TEXT 整块保留，仅当超过硬性兜底 `TEXT_MAX_BYTES=12000` 才按行二次切分（防超 Milvus 上限）。
2. **整体替换旧管线**
   - 删除 `DocumentCleaner.java` + `DocumentCleanerTest.java`（R1/R2/R3 具体逻辑并入状态机）。
   - 改造 `DocumentIngestService`：构造注入 `MarkdownStateMachineParser`；`parser.parse(text)`（clean=true）/`parser.bypass(text)`（clean=false）替换原 `cleaner.clean()+semanticSplit`；`simulateEmbedFailure` 检查提前到 embed/入库前；insert 行新增 `title = chunk.titleChain()`。
   - 移除 `semanticSplit`、`cosineSimilarity`（旧按语句+余弦合并切分逻辑全部删除）。
3. **Milvus schema / 检索**（`MilvusRestStore`）
   - `createCollection` 新增 `title` 字段：`VarChar max_length=1024`。
   - `text` 字段 `max_length` 2048 → **16384**（容纳整块标题区块）。
   - `search()` 的 `outputFields` 与返回 map 新增 `title`，供溯源。
4. **测试改造**
   - `DocumentIngestServiceTest` / `DocumentIngestServiceLogicTest` 改用 parser；删除 `semanticSplit`/`cosineSimilarity` 相关用例；保留 C13（clean.enabled=false 走 bypass、不调 parse）、C14（clearBeforeIngest=false 不 drop）、embed 失败路径（抛错且 Milvus 未清空）。
   - 全量 `mvn test`：**41 个测试，0 失败、0 错误**。

### §2 关键 Bug 修复（真实文档验证）

- **标题识别失效修复**：原用 `HEADING.matcher(t).matches()`，因正则 `^#{1,6}\s+\S` 无尾部 `.*`，`matches()` 要求全串匹配，所有真实标题行（如 `# 一、本科新生入学须知`）判定失败 → 整篇正文坍缩为 1 个 TEXT 块（内含 82 个标题行）、标题链为空。
  - 修复：标题判定 `HEADING.matcher(t).matches()` → `.find()`；`skipToc` 内 `SECTION_HEADING.matcher(...).matches()` → `.find()`（两者均以 `^` 锚定，`find()` 安全）。`TOC_HEADING`（`^(目录|contents)$` 全串匹配）保持 `.matches()`。
  - 实测 `d:\A\jmu新生手册.txt`：**88 块**，每标题区块独立、`titleChain` 完整（如 `四、…华侨港澳台新生入学须知 > 华侨港澳台本科收费标准`），2 张表各自为独立 TABLE chunk，曲调/目录正确剔除，TEXT 块无残留标题行。
- 顺带修正：修复过程中曾误引入 `semanticSplit`/`cosineSimilarity` 重复定义（旧版残留与新插入并存），已清理为单份并整体删除。

### §3 变更文件清单

- 新增：`MarkdownStateMachineParser.java`
- 删除：`DocumentCleaner.java`、`DocumentCleanerTest.java`
- 修改：`DocumentIngestService.java`、`MilvusRestStore.java`、`DocumentIngestServiceTest.java`、`DocumentIngestServiceLogicTest.java`
- 配置：`RagProperties.Clean` 语义不变（当前不新增配置项；正文块上限为解析器内常量 `TEXT_MAX_BYTES=12000`，表格块上限仍取 `rag.clean.table-max-bytes`）。

### §4 待办

- **新增 `MarkdownStateMachineParserTest`**：按状态（TITLE/TOC/TABLE/MUSIC_SCORE/TEXT）覆盖 parser 单测（当前尚无独立单测，依赖现有集成/逻辑测试）。
- **真实环境联调**：`mvn spring-boot:run` → 上传 `jmu新生手册.txt` → `GET /api/knowledge/list` 核对切分；检索命中核对 `title` 溯源。
- **JaCoCo 覆盖率复核**：全局 LINE≥30%，`MarkdownStateMachineParser` LINE≥70%（目标≥85%）。
- **文档联动**：`document-cleaning-plan.md` §10 追加本方案为 v5（状态机整体替换 + 按标题整块切分），标注旧 §10.2 的 4 个失败由新实现一并消解。

### §5 注意

- `text` 上限与 `title` 字段改动需**重启应用 + 重新上传文档**才生效（旧错误块由 ingest drop-reload 清除）。
- 此前入库的是 bug 版本结果（仅 3 块、标题链为空），重新上传后才能看到正确 88 块切分。