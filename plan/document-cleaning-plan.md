# 文档入库清洗（曲调过滤 / 目录过滤 / 表格保留 / 入库前清空）计划

> 状态：**计划中（v4，评审三轮修订后定稿，进入编码）**（2026-09-11）
> 项目：d:\A\rag-xmut（Spring Boot 4.1.1 + Spring AI 2.0 + Milvus 3.0.1 REST v2 + LM Studio）
> 范围：仅在**文档入库（ingest）管线**内新增/改造；不影响检索与多轮问答逻辑。

## 0. 修订记录

| 版本 | 日期 | 说明 |
|---|---|---|
| v1 | 2026-09-11 | 初版 |
| v2 | 2026-09-11 | 评审一轮：逐条修复（R1 收紧、R2 双终止、表格提前拆分、embed 先于 Drop、source 传递、R3 判据、清洗顺序、兼容路径、覆盖率、笔误） |
| v3 | 2026-09-11 | 评审二轮定稿项：①R2 终止改为"先 `#`，条目形态仅兜底"，条目正则放宽为 `^.+?[\s.·]{1,}\d{1,3}$`；②R3 表格识别改扫描-状态机（回溯表头 + 遇新表头/分隔行切块），补 C8b/C9b；③R1 明确两条规则职责边界，补 CJK=3/4 临界负例；④长度判定改**字节**（`table-max-bytes` 默认 1500），§4.6 assert 同步字节；⑤`clean.enabled=false` 复用原 Document 对象不重建；另：drop 改 try-drop 幂等、清洗日志汇总、§6.2 失败路径改用可注入模拟开关、覆盖率目标 85%/卡点 70%、纯函数 static |
| v4 | 2026-09-11 | 评审三轮（编码前确认三项已入正文）：A. `tryDrop()` 幂等**需联调确认 Milvus 3.0.1 REST v2 对 drop 不存在集合的实际返回**，非幂等则在 call 外层吞 not-found 错误码，联调先对空集合 ingest 验证；B. R2 主规则终止条件改为"删到下一个 `#` 或 `##` 章节标题"（`^#{1,2}\s+\S`），防多级目录吞正文；C. R3 吸收数据行要求**列数 == 表头列数**（按 `\|` 分割计数），防误吞 `a \| b \| c` 正文行。另：`simulateEmbedFailure` 移入 `rag.test.*` 命名空间，生产不混业务配置、上线前确认 `false`；1500 字节给出上下调节方向；C6 补装饰行子用例、新增 C14 测 `clearBeforeIngest=false`；循环推进方式明确"从上一块终止位继续扫描"；文档日志带 source |

## 1. 目标

在上传文档并入库（`POST /api/knowledge/ingest`）时，对读取到的文档内容执行**数据清洗 + 切片**，满足 4 点：

1. **过滤校歌曲调，只保留歌词**：删除简谱/调性/拍号等"曲调行"，保留中文歌词行。
2. **过滤目录**：删除"目录"标题及其条目（标题+页码）。
3. **保持表格内容完整**：Markdown 表格作为**单个原子 chunk**整体保留（超长时按行二次切分，保留表头+分隔行语义）。
4. **入库安全清空**：先全量完成向量化，成功后再 Drop 重建 collection 清旧数据，避免清理失败导致数据丢失。

清洗在文档 IO 读取（`TikaDocumentReader`）之后、切片向量化之前做行级预处理。

## 2. 现状分析与真实文件校准结果（grep `d:\A\jmu新生手册.txt`）

| 模块 | 现状 | 差距 |
|---|---|---|
| `DocumentIngestService.ingest()` | Tika → `semanticSplit()` → embed → insert | 无清洗；曲调/目录/表格行全被当正文切片 |
| `semanticSplit()` | 每个 `Document` 独立按句切分+余弦合并 | 表格每行被切碎；曲调/目录污染 |
| `MilvusRestStore` | ensure/insert/search/query，无 drop | 需新增 `dropCollection()` |
| 当前数据 | `rag_xmut` 已导入 503 chunks | Drop 重建清空并重置 id |

**校准（grep）结论：**
- 校歌区块 lines 9~21：`## 集美学校校歌` → 内部无任何 `#` 子标题 → `## 校徽说明`（line 23）结束。歌词与简谱行交错。
- 目录区块 lines 36~48：`## 目录` → 条目均 `正文+两位页码`（line 37~46）→ blank（47）→ `# 一、本科新生入学须知`（48）。
- 表格：line 103 `# 收费标准一览表（本科）` 下为 `|---|` 对齐分隔行 + 多数据行；line 111 `2025级新生报到点安排表` 下有若干学院块（含 `专业：`/`报到点：` 等非表格行）。

## 3. 总体方案

基础决策：通用清洗（`rag.clean.enabled` 默认 `true`，可整机关闭）、Drop 重建清空、表格原子块。

评审摊平后的关键点：
1. **清洗顺序**：R2 目录过滤 → **R3 表格抽取（先抽出，保护表格不被 R1 误删）** → R1 曲调过滤（作用于剩余文本）。
2. **清空时机**：先全量 embed 成功，再 Drop + ensure + insert（§4.3）。
3. **长度以字节计**（与 Milvus `VARCHAR max_length` 对齐，§4.1 R3 / §4.6）。

```
POST /api/knowledge/ingest (file)
   ▼
DocumentIngestService.ingest()
   ① resolveSource(文件名 → source)
   ② Tika 读取 rawDocs
   ③ 逐 Document：
        clean.enabled=true  → cd = cleaner.clean(doc.getText())
                              chunks = semanticSplit(List.of(new Document(cd.text))) + cd.tables
        clean.enabled=false → chunks = semanticSplit(List.of(doc))   // 复用原对象，不重建
   ④ 全量 embed 所有 chunk → 内存 rows(text, vector)   // 失败即 abort，未动旧数据
   ⑤ clearBeforeIngest → tryDrop()                     // embed 成功后才清空
   ⑥ ensureCollection(dimension)  // 重建 + createIndexIfMissing + load
   ⑦ 分批 insert（统一补 source=resolveSource(...)）→ loadCollection
```

## 4. 详细设计

### 4.1 清洗规则（`DocumentCleaner`，行级，顺序 R2→R3→R1）

前置：统一换行 `replaceAll("\\r\\n?", "\\n")`，按行处理。

**R1 曲调行过滤（保留歌词）** — `removeSongMelody(text)`
两条规则职责边界明确、不重叠：
- **全局规则**：删除含 ≥1 数字且**通篇无 CJK** 的符号行——正则 `(?=.*\d)[0-9 .|–\-/()=·]+`。负责 `1=F`、`5.5 5.5` 等 **CJK=0** 行。
- **校歌区块规则**：仅当进入"标题含 `校歌`"区块（该标题行起至下一个以 `#` 开头行止，grep 确认无子标题）时，行内 **CJK 字符数 `< 4`** 即删除。负责 `2/4(庄严)`、`(庄严)` 等 **CJK=2** 行。**临界值**：`CJK=3`（如"大家勿"）删；`CJK=4`（如"大家勿忘"）保留。
- 正文含 CJK 的数字行（`地址:…1号`、`电话 0592-…`、`2025 年 9 月`）因含 CJK 判断保留。

**R2 目录过滤** — `removeToc(text)`
- 标题识别：`^\s*#\s*\d?\s*目录\s*$`（大小写不敏感，兼容 `CONTENTS`）。
- **终止条件（主判章节标题，条目形态仅兜底）**：
  - 主规则：TOC 区 = 从目录标题行起，**删除其后的所有非空行，直到下一个"章节标题行"**——匹配 `^#{1,2}\s+\S`（即 `#` 或 `##` 级别）的行，该行不删。用 `#{1,2}` 是为了**防止多级目录下 `## 第一章` 被吞**（v4 编码前确认项 B）。
  - 兜底规则（仅当标题之后**找不到** `^#{1,2}\s+\S` 章节标题时）：删除途中遇到的、匹配条目形态 `^.+?[\s.·]{1,}\d{1,3}$` 的行；遇到**首个不匹配**该形态的非空行即停止删除（该行保留）。装饰行（如 `————`）在无 `#` 章节时按条目形态不匹配会终止——存在"后续条目漏删"风险，见 §6.1 C6b 用例。
- blank 行属被删目录区。
- **循环推进**：多个目录块循环时，每次扫描都从**上一块终止位置之后**继续，不全文 regex 替换，避免把正文中偶然出现的"目录"二字误当标题。

**R3 表格抽取（原子保留 + 超长二次切分）** — `extractTables(text)`
- **扫描-状态机**（解决"表头回溯"与"两表连续"边界）：
  1. 逐行扫描；一行匹配 `^\s*\|.+\|\s*$`（容忍行首空格）或含 ≥2 个 `|`，且**下一行是对齐分隔行**（匹配 `^\s*\|?\s*:?-+:?-*\s*(\|\s*:?-+:?-*\s*)*\|\s*$`）→ **开启新表块**；
  2. **回溯**：把当前行作为表头吸入；
  3. **吸收**：吸入对齐分隔行，再持续吸收后续数据行；被吸收的数据行须满足**列数 == 表头列数**（按 `\|` 分割后计数，空白列忽略），防止误吞 `a | b | c` 正文行（v4 编码前确认项 C）；
  4. **切块**：遇 空行 / 非表行（列数不符也算） / 又出现"表头+分隔行"模式（即新表开始）时结束当前表块。两张表**无空行连续**时，遇到新的"表头+分隔行"自动切分。
- 表块以原行结构拼接（行间 `\n`，保留 `|`），每块作为一个待入库 chunk。
- **长度（字节）二次切分**：单块 `text.getBytes(UTF_8).length > rag.clean.table-max-bytes`（默认 1500）时，按数据行分组切成多个子块，**每个子块都保留表头行 + 对齐分隔行**，直至子块字节 ≤ 上限。

### 4.2 新增 `com.wuyunbin.rag.service.DocumentCleaner`

```java
@Service
public class DocumentCleaner {
    public CleanedDocument clean(String rawText) { ... }   // R2→R3→R1，含清洗行数日志
    public record CleanedDocument(String text, List<String> tables) {}

    // 纯函数：static + 包可见，测试无需 new
    static boolean isMelodyLine(String line);         // 全局纯曲调行（CJK==0 且含数字）
    static boolean isTableAlignRow(String line);      // 对齐分隔行
    static boolean isTocEntry(String line);           // ^.+?[\s.·]{1,}\d{1,3}$

    // 包可见（组合逻辑），便于单测：
    String removeToc(String text);
    String removeSongMelody(String text);
    List<String> extractTables(String text);          // 含字节二次切分
}
```

**日志汇总**：`clean()` 返回时 `log.info` 输出 `[doc-clean] tocLines=…, melodyLines=…, tables=…, splitTables=…`，便于联调核对切片数差异。

### 4.3 改造 `DocumentIngestService.ingest()`（关键：embed 先于 Drop）

```java
List<String> allChunks = new ArrayList<>();
for (Document doc : rawDocs) {
    String text = doc.getText();
    if (text == null || text.isBlank()) continue;
    if (props.clean().enabled()) {
        CleanedDocument cd = cleaner.clean(text);
        allChunks.addAll(semanticSplit(List.of(new Document(cd.text()))));
        allChunks.addAll(cd.tables());
    } else {
        allChunks.addAll(semanticSplit(List.of(doc)));   // 复用原对象，不重建（真兼容）
    }
}
int dimension = resolveDimension();

// ④ 全量向量化到内存；失败即 abort，未触碰 Milvus（旧数据仍在）
List<Map<String,Object>> rows = new ArrayList<>();
for (List<String> batch : partition(allChunks, props.ingest().batchSize())) {
    List<float[]> vectors = embeddingModel.embed(batch);      // 异常即抛，rows 不写库
    for (int i=0;i<batch.size();i++) rows.add(row(batch.get(i), vectors.get(i))); // 仅 text+vector
}
// ⑤ embed 全部成功后，才清空
if (props.ingest().clearBeforeIngest()) milvusRestStore.tryDrop();
milvusRestStore.ensureCollection(dimension);
// ⑦ 分批 insert row，统一补 source = resolveSource(file.getOriginalFilename())；loadCollection()
```

**metadata/source**：清洗/重包 `new Document(cd.text())` 会丢掉原 Document metadata —— 因此 `insert` 时**依赖 `resolveSource(文件名)` 的返回值**统一写入 source 字段（与现状一致，**不得**依赖 `doc.getMetadata()`）。在 `embedAndInsert` 内显式注释标注。

**原子性**：embed 在 Drop 前完成，失败不丢旧数据。内存量级：503 chunks×1024 维×4B≈2MB，OK；超大批量文档时评估峰值（§对照第 8 点）。

### 4.4 新增 `MilvusRestStore.tryDrop()`

```java
/** 幂等删除集合；集合不存在视为成功。 */
public void tryDrop() {
    // ⚠️ 依赖 Milvus 3.0.1 REST v2：对不存在的 collection drop 是否返回 code=0（幂等）需在联调确认。
    // 若返回"collection not found"错误码或 4xx，需在此 catch/判断 code 后静默吞掉该类错误。
    call("/v2/vectordb/collections/drop", Map.of("collectionName", collectionName));
}
```
Drop 后由 `ensureCollection()` 重建表（含 `createIndexIfMissing()` 建索引 + `loadCollection()`），**索引随之重建，无需额外处理**。

**联调确认（v4 编码前确认项 A）**：联调第一步先对**空集合**跑一次 ingest，验证 `tryDrop()` 不抛异常；若 Milvus 对 drop 不存在集合报"collection not found"，则在 `call` 外层捕获并忽略该错误码。

### 4.5 配置新增（`RagProperties` + `application.properties`）

```java
// RagProperties 紧凑构造器（final 字段用条件赋值，不能先赋默认再覆盖）：
//   this.clean = (clean != null) ? clean : new Clean(true, 1500);

public record Ingest(int batchSize, int defaultTopK,
        @DefaultValue("true") boolean clearBeforeIngest) {}
public record Test(@DefaultValue("false") boolean simulateEmbedFailure) {}  // rag.test.*，仅联调，生产必须 false
public record Clean(@DefaultValue("true") boolean enabled,
                    @DefaultValue("1500") int tableMaxBytes) {}  // 单位：字节
```

```properties
rag.ingest.clear-before-ingest=true
rag.clean.enabled=true
rag.clean.table-max-bytes=1500             # 单表块最大字节数，超过按行二次切分
rag.test.simulate-embed-failure=false      # rag.test.* 与生产配置隔离；联调验证 embed 失败路径，上线必须为 false
```

### 4.6 兼容与边界

- `clean.enabled=false`：逐 Document 复用**原 Document 对象**走 `semanticSplit(List.of(doc))`，等价当前行为（真兼容）。
- **长度以字节判定**：`text.getBytes(UTF_8).length > table-max-bytes(1500)` 触发二次切分；`insert` 前对每个待入库 chunk（含表格）校验 `getBytes(UTF_8).length ≤ 1500`，超限 `log.error` 并抛异常（不静默超写），与 Milvus `max_length=2048` 留余量。**阈值可调**：1500 字节较保守，若联调发现表格被切得过碎（子块仅 2-3 行），可上调至 **1800**；若插入报长度错误，则下调。
- 段落 chunk 沿用既有 `semanticSplit` 的 1200 字符上限（既有行为，无需字节处理）。
- rawDocs 空/blank、无目录标题、无表格、无校歌标题：各步骤幂等返回原文本。
- `tryDrop()` 幂等性联调确认（§4.4）；`clearBeforeIngest=true` 但集合不存在时 drop 静默成功。
- `simulateEmbedFailure=true` 时 embed 抛异常，验证 §6.2 失败路径；**上线前必为 `false`**。

### 4.7 代码骨架注释（评审点 11 落位）

在 `DocumentIngestService.ingest()` 中重包 Document 处显式注释：
```java
// 注意：此处 new Document(cd.text()) 仅承载 text，Document metadata 不参与；
//        source 由 insert 阶段用 resolveSource(文件名) 统一补齐，勿依赖 doc.getMetadata()。
```

## 5. 影响面与兼容

- 不触碰 `ChatService`、`MilvusRestStore.search/query`。
- `clean.enabled=false` + `clearBeforeIngest=false` 可逐项回退。
- **数据副作用**：Drop 重建清空现有 503 chunks 并重置 id；联调后须重新入库。id 由 Milvus auto-id 分配，断言**"新 id 与旧数据无交集"**而非"从 1 开始"。

## 6. 测试计划

### 6.1 单元测试（`DocumentCleanerTest`）
| 编号 | 类型 | 用例 | 断言 |
|---|---|---|---|
| C1 | 曲调/歌词 | 真实校歌区块混排（line9~21 样例） | 简谱/CJK=0 行删；`1=F`、`2/4(庄严)`、`(庄严)` 删；歌词行（"闽海之滨…""大家勿忘"）保留 |
| C2 | 曲调临界 | `大家勿`(CJK=3)、`大家勿忘`(CJK=4) | 前者删、后者保留 |
| C3 | 曲调误删 | `地址：…1 号`、`电话 0592-…`、`2025 年 9 月` | 全保留 |
| C4 | 全局纯符号 | `-----`(无数字) | 保留 |
| C5 | 目录主判定 | `## 目录`+条目+`# 章节` | 目录块删、`#` 保留 |
| C6 | 目录兜底 | 目录后无章节标题，条目后接正文行 | 按条目形态删除，首个非条目正文行保留 |
| C6b | 目录装饰行 | TOC 内含 `————` 装饰行 + 无章节标题 | 主规则按章节标题删除时装饰行被吞；兜底路径下装饰行终止→后续条目漏删，需在实现中处理（见 §4.1 R2 兜底说明），用例记录实际行为 |
| C6c | 多级目录 | 目录后有 `## 第一章` 而非 `#` | 主规则按 `^#{1,2}\s+\S` 在 `## 第一章` 处停止，正文不被吞 |
| C7 | 目录误判 | 无"目录"标题 | 原样返回 |
| C8 | 表格抽取 | 表头+对齐分隔+多数据行、行首带空格 | 单一 table、结构完整、表头被回溯带入 |
| C8b | 两表连续 | 两张表间**无空行** | 遇新"表头+分隔行"自动切为两表 |
| C9 | 表格判据 | 正文 `a \| b` 单行、无分隔行 | 不识别为表 |
| C9b | 表头回溯 | 仅数据行（无表头/分隔行） | 不误开启 |
| C9c | 列数约束 | 表块后紧跟 `a \| b \| c` 正文行（列数≠表头） | 该正文行不被吞进表块 |
| C10 | 表格拆分 | 表字节 > tableMaxBytes | 拆多子块，**每块含表头+分隔行**；用字节口径 |
| C11 | 抽取顺序 | 曲调行+表格混合 | R3 先抽走表（`\| 1 \| 2 \|` 纯数字表头不被 R1 删）→ 剩余文本才做曲调过滤 |
| C12 | 幂等 | 无表/无目录/无校歌 | 原样返回 |
| C13 | ingest | `clean.enabled=false` | 复用原 Document、逐 Document 原切分、不调 cleaner |
| C14 | ingest | `clearBeforeIngest=false` | ingest 不调用 tryDrop，旧数据保留 |

### 6.2 集成/联调（真实 Milvus + LM Studio）
1. 重启应用，`mvn spring-boot:run`；先传临时小文档再传 `d:\A\jmu新生手册.txt`。
2. `GET /api/knowledge/list`：id 与旧数据无交集；切片无 `5. 5  5. 5` 曲调行、无 `## 目录` 块；含"闽海之滨…""大家勿忘"歌词；含完整"收费标准一览表"、各学院块表格。
3. **失败路径（可注入，不在联调时关模型）**：设 `rag.test.simulate-embed-failure=true` 上传 → 接口报错，验证 **Milvus 旧数据未清空**（embed 先于 Drop）；随后恢复 `false`。
4. **tryDrop 幂等确认（v4 确认项 A）**：第一步先对**空集合** ingest，确认 `tryDrop()` 不抛异常；若报"collection not found"，在 `call` 外层吞该错误码。
5. 检索命中：提问 `《2025级新生收费标准》` 命中表格内容。

### 6.3 覆盖率
- JaCoCo 沿用 `com/wuyunbin/rag/**` 路径式口径；`MilvusRestStore` 维持现有 excludes。
- **DocumentCleaner 卡点 LINE ≥ 70%，目标 ≥ 85%**（C1~C12 覆盖分支：有无目录、有无校歌、CJK 临界两侧、表格有无/连续/拆分）。全局 ≥ 30% 不变。

## 7. 验收标准

- [ ] 入库前旧 Milvus 数据被清空（tryDrop + ensure 重建 + 索引重建），id 与旧数据无交集。
- [ ] 切片无校歌曲调/调性/拍号行，歌词完整；正文含数字行未误删。
- [ ] 切片无目录块；正文未误删。
- [ ] 表格整体保留；超长表按行二次切分且带表头/分隔行（字节口径，≤1500 字节）。
- [ ] embed 失败时旧数据不被清空（`simulateEmbedFailure` 可验证）。
- [ ] `rag.clean.enabled` / `rag.ingest.clear-before-ingest` / `rag.clean.table-max-bytes` / `rag.test.simulate-embed-failure` 均可配置。
- [ ] `tryDrop()` 对空集合幂等（联调确认后生效，或已在 `call` 外吞 not-found）。
- [ ] `rag.test.simulate-embed-failure` 上线前必须为 `false`。
- [ ] `mvn test` 全绿；全局 LINE≥30%，DocumentCleaner LINE≥70%。

## 8. 实施顺序

1. `MilvusRestStore.tryDrop()`。
2. `DocumentCleaner`（R2→R3→R1，static 纯函数 + 日志）＋ 单测 C1~C12。
3. `DocumentIngestService`（逐 Document、embed→Drop→ensure→insert、`simulateEmbedFailure`、兼容分支复用原对象）＋ C13。
4. `RagProperties` + `application.properties`。
5. `mvn test` 验证覆盖率与回归。
6. 联调（§6.2）校准字节阈值/表格拆分并验证失败路径。

## 9. 风险与待办

- **tryDrop 幂等性**（v4 确认项 A）：取决于 Milvus 3.0.1 REST v2 对 drop 不存在集合的实际返回；非幂等则在 `call` 外吞 not-found。联调第一步对空集合验证。
- **insert 在 Drop 后失败**（embed 成功的极端残余窗口）→ 集合为空/部分。已用"embed 先于 Drop"显著缩小；运维说明：**失败后需重新 ingest**，不静默宣称成功。
- **上线前 `rag.test.simulate-embed-failure` 必须为 `false`**（v4 确认项）。
- R2 依赖"目录标题形态 + 条目页码形态"；不同文档形态不同则需按其 grep 校准 `removeToc`（当前文件已校准）。主规则已改为按 `^#{1,2}\s+\S` 章节标题终止，通用化鲁棒性更高。
- R1 区块阈值（CJK<4）基于当前样本；校歌区块如出现子标题/换行差异，联调微调边界。
- Tika 对 `.txt` 是否精确保留空行/`|` 行：联调先打印原始行再定 `extractTables` 边界。
- **内存**：分批 embed 全量攒内存再入库；503≈2MB，大批量文档需评估峰值占用。

## 10. 实施进度记录（2026-09-11）

> 编码已按 §8 顺序推进到第 5 步（单测验证），发现 4 个失败，尚未全部转绿。

### 10.1 已完成
| 交付物 | 状态 | 说明 |
|---|---|---|
| `MilvusRestStore.tryDrop()` | ✅ | 幂等删除，注释标注依赖 Milvus REST v2 drop 不存在集合的实际返回（确认项 A 待联调）。当前实现未吞错误码，若联调报 not-found 需按 §4.4 处理 |
| `DocumentCleaner` | ✅ | R2→R3→R1，static 纯函数 + `[doc-clean]` 汇总日志；与计划 §4.1 对齐 |
| `DocumentIngestService.ingest()` | ✅ | 逐 Document 清洗 + embed 先于 Drop + `tryDrop` 重建 + `simulateEmbedFailure` 开关 + `clean.enabled=false` 复用原对象（§4.3/§4.7 注释已落位） |
| `RagProperties` + `application.properties` | ✅ | `rag.clean.*`、`rag.ingest.clear-before-ingest`、`rag.test.simulate-embed-failure` 全配置（§4.5）；`Chat` record 已横向扩展 `logRetrieval`/`logRetrievalMaxRaw`（与清洗无冲突） |
| 单测 | ✅ 已写 | `DocumentCleanerTest`(C1~C12)、`DocumentIngestServiceTest`(C13/C14)、`DocumentIngestServiceLogicTest` |

### 10.2 单测结果与失败项
`mvn test -Dtest=DocumentCleanerTest,DocumentIngestServiceLogicTest,DocumentIngestServiceTest`：28 例，**4 失败**。

| 失败用例 | 根因 | 修复方向 |
|---|---|---|
| `noTocHeadingUnchanged:97` / `plainTextUnchanged:175` | `removeSongMelody` 对每行（含末尾空行）都补 `\n`，输出比输入多一个换行（`endswith("\n\n")`），破坏"精确相等"断言 | 规整末尾换行：仅对非空末尾追加 `\n`，或收敛输出换行规则 |
| `dataRowsWithoutHeaderNotTable:132` | `MELODY_LINE` 字符类含 `|`，使 `\| 1 \| 2 \|` 这种**无表头/分隔行的孤立数字管道行**过关 R3 后被 R1 当曲调删除（误删） | R1 排除含 `|` 的管道行（与表格判定互斥），或收紧正则 |
| `simulateEmbedFailureAbortsBeforeDrop:94` | `simulateEmbedFailure` 检查放在 `semanticSplit()` **之后**，而 `semanticSplit` 内部（余弦切分）已调 `em.embed`，开关没挡住 embed，断言 `never().embed` 失败 | 把开关检查提前到任何 embed（含 split 内嵌 embed）之前，或调整用例断言口径 |

### 10.3 待办
- [ ] 修复 §10.2 的 4 个失败用例对应的 3 处逻辑缺陷（末尾换行、R1 误删管道数字行、simulate 检查时机）。
- [ ] 联调（§6.2）：确认 `tryDrop()` 对空集合幂等（确认项 A），非幂等则按 §4.4 吞 not-found。
- [ ] 联调校准 `table-max-bytes`（1500 偏保守，遇表格切得过碎可上调 1800；插报长度错误则下调，见 §4.6）。
- [ ] `mvn test` 全绿 + JaCoCo（全局 ≥30%，`DocumentCleaner` ≥70%）复核。