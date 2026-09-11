package com.wuyunbin.rag.service;

import com.wuyunbin.rag.config.RagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 基于状态机（FSM）的 Markdown 文档语义分块解析器（依据 plan/StateMachine.md）。
 * <p>
 * 单遍、逐行驱动：一边读取一边按标题层级切分为语义块，同时完成
 * 目录过滤（TOC）/ 表格识别（TABLE）/ 曲调剔除（MUSIC_SCORE）/ 正文分块（TEXT）。
 * 状态集：TITLE、TOC、TABLE、MUSIC_SCORE、TEXT。
 * 优先序：TABLE &gt; TOC &gt; MUSIC_SCORE &gt; TITLE &gt; TEXT（表格/目录优先，曲调不误吞表格）。
 * <p>
 * 输出 {@link Chunk}：正文块与表格块都以普通 text chunk 承载，
 * 每块附 {@code titleChain}（标题链元数据）便于入库与溯源；曲调不产出 chunk。
 */
@Service
public class MarkdownStateMachineParser {

    /** 状态集。 */
    private enum MarkdownState { TEXT, TITLE, TOC, TABLE, MUSIC_SCORE }

    private static final Logger log = LoggerFactory.getLogger(MarkdownStateMachineParser.class);

    /** 语义块。type 区分正文/表格；titleChain 为标题链；text 为待向量化+入库的正文。 */
    public record Chunk(int index, ChunkType type, String titleChain, String text) {
        public enum ChunkType { TEXT, TABLE }
    }

    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+\\S");
    private static final Pattern SECTION_HEADING = Pattern.compile("^#{1,2}\\s+\\S");
    private static final Pattern TOC_HEADING = Pattern.compile("(?i)^(目录|contents)$");
    private static final Pattern TOC_ENTRY = Pattern.compile("^.+?[\\s.·]{1,}\\d{1,3}$");
    private static final Pattern TABLE_ALIGN_ROW = Pattern.compile(
            "^\\s*\\|?\\s*:?-+:?-*\\s*(\\|\\s*:?-+:?-*\\s*)*\\|?\\s*$");
    /** 调号/拍号触发行（如 1=F、2/4）；允许末尾带 (注解)。 */
    private static final Pattern SCORE_TRIGGER = Pattern.compile("^\\s*\\d\\s*=\\s*\\S|^\\s*\\d\\s*/\\s*\\d");

    /**
     * TEXT 正文块的硬性字节兜底上限：以「两个标题之间为一块整体」，正常不中途切分；
     * 仅当单个标题区块（含换行前评估）超过该上限时才按字节二次切分，避免超出
     * Milvus text 字段 max_length（见 MilvusRestStore，需保持 < max_length）。
     */
    private static final int TEXT_MAX_BYTES = 12000;

    private final RagProperties props;

    public MarkdownStateMachineParser(RagProperties props) {
        this.props = props;
    }

    /** 状态机解析入口：返回按标题切分后的语义块。index 从 1 递增。 */
    public List<Chunk> parse(String rawText) {
        String text = normalize(rawText);
        String[] lines = text.split("\n", -1);
        List<Chunk> out = new ArrayList<>();
        int tocLines = 0;
        int scoreLines = 0;
        int limit = props.clean().tableMaxBytes();
        int n = lines.length;

        // 标题栈 + 当前正文缓冲 + 曲调区域标记
        List<Integer> titleLevels = new ArrayList<>();
        List<String> titleNames = new ArrayList<>();
        StringBuilder textBuf = new StringBuilder();
        boolean scoreRegion = false;
        MarkdownState state = MarkdownState.TEXT;

        int i = 0;
        while (i < n) {
            String line = lines[i];
            String t = line.trim();

            // 1) 标题行 → TITLE / TOC
            if (!line.isBlank() && HEADING.matcher(t).find()) {
                String content = headingContent(t);
                if (TOC_HEADING.matcher(content).matches()) {
                    // 进入 TOC：删除目录条目块，直到下一章节标题或兜底停止
                    int next = skipToc(lines, i);
                    tocLines += next - (i + 1);
                    state = MarkdownState.TOC;
                    i = next;
                    state = MarkdownState.TEXT;
                    continue;
                }
                // 章节标题：flush 前一块正文，更新标题栈，标题文本不入正文
                flushText(out, textBuf, titleChain(titleNames));
                updateTitleStack(titleLevels, titleNames, headingLevel(t), content);
                scoreRegion = content.contains("校歌");
                state = MarkdownState.TITLE;
                i++;
                state = MarkdownState.TEXT;
                continue;
            }

            // 2) 表格开始（表头 + 对齐分隔行）→ TABLE
            if (isTableRow(line) && i + 1 < n && isTableAlignRow(lines[i + 1])) {
                i = consumeTable(lines, i, out, titleChain(titleNames), limit);
                state = MarkdownState.TABLE;
                state = MarkdownState.TEXT;
                continue;
            }

            // 3) 曲调判断 → MUSIC_SCORE（剔除）
            if (line.isBlank()) {
                appendLine(textBuf, line, out, titleChain(titleNames));
                i++;
                continue;
            }
            if (scoreRegion) {
                if (containsDigit(t) || countCjk(line) < 4) {
                    scoreLines++;
                    i++;
                    continue;
                }
            } else if (isScoreTrigger(t)) {
                scoreRegion = true;
                scoreLines++;
                i++;
                continue;
            } else if (isGlobalScore(t)) {
                scoreLines++;
                i++;
                continue;
            }

            // 4) 其余 → TEXT 累积
            appendLine(textBuf, line, out, titleChain(titleNames));
            i++;
        }
        flushText(out, textBuf, titleChain(titleNames));

        int tables = (int) out.stream().filter(c -> c.type() == Chunk.ChunkType.TABLE).count();
        log.info("[markdown-state] chunks={}, tocLines={}, tables={}, scoreLines={}",
                out.size(), tocLines, tables, scoreLines);
        return out;
    }

    /** clean.enabled=false 的兼容回退：整篇原文作为单个 TEXT chunk，不做任何清洗/分块。 */
    public List<Chunk> bypass(String rawText) {
        return List.of(new Chunk(1, Chunk.ChunkType.TEXT, "", normalize(rawText)));
    }

    /** TOC：从目录标题行之后扫到下一章节标题；无章节标题则按条目形态删连续条目行。 */
    private int skipToc(String[] lines, int i) {
        int k = i + 1;
        while (k < lines.length && !SECTION_HEADING.matcher(lines[k].trim()).find()) {
            k++;
        }
        if (k < lines.length) {
            return k; // 落在章节标题行，主循环作为新标题处理
        }
        int j = i + 1;
        while (j < lines.length && (lines[j].isBlank() || TOC_ENTRY.matcher(lines[j].trim()).matches())) {
            j++;
        }
        return j;
    }

    /** TABLE：吸收「表头 + 对齐分隔行 + 列数一致的数据行」，退出时二次切分并出块。 */
    private int consumeTable(String[] lines, int i, List<Chunk> out, String chain, int limit) {
        List<String> block = new ArrayList<>();
        block.add(lines[i]);     // 表头
        block.add(lines[i + 1]); // 对齐分隔行
        int headerCols = columnCount(lines[i]);
        int j = i + 2;
        while (j < lines.length) {
            String cand = lines[j];
            if (cand.isBlank() || HEADING.matcher(cand.trim()).matches()) {
                break; // 空行或标题结束表格
            }
            if (isTableRow(cand) && j + 1 < lines.length && isTableAlignRow(lines[j + 1])) {
                break; // 新表开始
            }
            if (isTableRow(cand) && columnCount(cand) == headerCols) {
                block.add(cand);
                j++;
            } else {
                break; // 列数不符的正文行，不吞
            }
        }
        addTableBlockSplit(block, limit, out, chain);
        return j;
    }

    /** 表块超长按数据行二次切分，每个子块都保留表头+对齐分隔行。 */
    private void addTableBlockSplit(List<String> block, int limit, List<Chunk> out, String chain) {
        String head = block.get(0);
        String align = block.get(1);
        List<String> cur = new ArrayList<>();
        cur.add(head);
        cur.add(align);
        for (int k = 2; k < block.size(); k++) {
            String row = block.get(k);
            String candidate = String.join("\n", cur) + "\n" + row;
            if (candidate.getBytes(StandardCharsets.UTF_8).length > limit && cur.size() > 2) {
                out.add(new Chunk(out.size() + 1, Chunk.ChunkType.TABLE, chain, String.join("\n", cur)));
                cur = new ArrayList<>();
                cur.add(head);
                cur.add(align);
            }
            cur.add(row);
        }
        if (!cur.isEmpty()) {
            out.add(new Chunk(out.size() + 1, Chunk.ChunkType.TABLE, chain, String.join("\n", cur)));
        }
    }

    // ---- 正文缓冲 ----

    private void flushText(List<Chunk> out, StringBuilder buf, String chain) {
        if (buf.length() == 0) {
            return;
        }
        String s = buf.toString().trim();
        buf.setLength(0);
        if (s.isEmpty()) {
            return;
        }
        out.add(new Chunk(out.size() + 1, Chunk.ChunkType.TEXT, chain, s));
    }

    private void appendLine(StringBuilder buf, String line, List<Chunk> out, String chain) {
        if (line.isBlank()) {
            if (buf.length() > 0 && buf.charAt(buf.length() - 1) != '\n') {
                buf.append('\n'); // 连续空行归并
            }
            return;
        }
        String suffix = buf.length() == 0 ? "" : "\n";
        if (buf.length() > 0) {
            String cand = buf.toString() + suffix + line;
            // 仅硬性兜底：整块超 TEXT_MAX_BYTES 才按行二次切分（正常标题区块整块保留）
            if (cand.getBytes(StandardCharsets.UTF_8).length > TEXT_MAX_BYTES) {
                flushText(out, buf, chain);
                suffix = "";
            }
        }
        buf.append(suffix).append(line);
    }

    // ---- 曲调判定 ----

    /** 区域外独立曲调行：含数字、全字符在白名单、且含 | 或独立延音线。 */
    static boolean isGlobalScore(String line) {
        String t = line.trim();
        if (!containsDigit(t)) {
            return false;
        }
        for (char c : t.toCharArray()) {
            if (!((c >= '0' && c <= '9') || c == '.' || c == '|' || c == '-' || c == ' ')) {
                return false;
            }
        }
        return t.contains("|") || t.contains(" - ");
    }

    /** 调号/拍号触发行（如 1=F、2/4），末尾可带 (注解)。 */
    static boolean isScoreTrigger(String line) {
        String t = line.trim().replaceAll("\\([^()]*\\)$", "").trim();
        return SCORE_TRIGGER.matcher(t).matches();
    }

    // ---- 表行判定 ----

    static boolean isTableAlignRow(String line) {
        return TABLE_ALIGN_ROW.matcher(line.trim()).matches();
    }

    /** 表行判据：含 ≥2 个 |。 */
    static boolean isTableRow(String line) {
        int c = 0;
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) == '|') {
                c++;
            }
        }
        return c >= 2;
    }

    /** 表列数：按 | 分割后非空片段数。 */
    static int columnCount(String line) {
        int c = 0;
        for (String seg : line.split("\\|", -1)) {
            if (!seg.isBlank()) {
                c++;
            }
        }
        return c;
    }

    // ---- 标题辅助 ----

    private static String normalize(String raw) {
        return raw.replaceAll("\\r\\n?", "\n").trim();
    }

    private static int headingLevel(String t) {
        int c = 0;
        for (char ch : t.toCharArray()) {
            if (ch == '#') {
                c++;
            } else {
                break;
            }
        }
        return c;
    }

    private static String headingContent(String t) {
        return t.replaceAll("^#+\\s*", "").trim();
    }

    private void updateTitleStack(List<Integer> levels, List<String> names, int level, String name) {
        while (!levels.isEmpty() && levels.get(levels.size() - 1) >= level) {
            levels.remove(levels.size() - 1);
            names.remove(names.size() - 1);
        }
        levels.add(level);
        names.add(name);
    }

    private static String titleChain(List<String> names) {
        return String.join(" > ", names);
    }

    private static boolean containsDigit(String s) {
        for (char c : s.toCharArray()) {
            if (c >= '0' && c <= '9') {
                return true;
            }
        }
        return false;
    }

    private static int countCjk(String s) {
        int c = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= 0x4E00 && ch <= 0x9FFF) {
                c++;
            }
        }
        return c;
    }
}