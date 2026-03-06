package cn.warriorview.script.diagnostic;

import java.util.Collection;
import java.util.Map;

/**
 * 源码上下文片段生成工具。
 *
 * <p>负责从源码文本和偏移量生成带 {@code ↑} 指示符的上下文输出，
 * 用于在诊断消息中精确标记出错位置。
 * <p>
 * 同时支持从 YAML 映射生成紧凑的伪 YAML 片段，供脚本解析阶段的错误诊断使用。
 */
public final class SourceView {

    private SourceView() {
    }

    /** 上下文片段最大展示宽度（字符数）。 */
    private static final int MAX_DISPLAY_WIDTH = 80;

    /**
     * 生成单行源码的上下文片段。
     *
     * <pre>
     *   {hp} / max(100, + 5
     *                 ↑
     * </pre>
     *
     * @param source 单行源码文本
     * @param offset 出错字符的 0-based 偏移
     * @return 双行字符串：源码行 + 指示符行
     */
    public static String snippet(String source, int offset) {
        if (source == null || source.isEmpty()) return null;
        offset = Math.max(0, Math.min(offset, source.length()));

        // 若源码过长，截取出错位置附近的窗口
        int start = 0;
        int end = source.length();
        String prefix = "  ";

        if (end > MAX_DISPLAY_WIDTH) {
            int half = MAX_DISPLAY_WIDTH / 2;
            start = Math.max(0, offset - half);
            end = Math.min(source.length(), start + MAX_DISPLAY_WIDTH);
            if (start > 0) prefix = "  ...";
        }

        String line = source.substring(start, end);
        int caretCol = offset - start + prefix.length();

        StringBuilder sb = new StringBuilder();
        sb.append(prefix).append(line).append('\n');
        sb.append(" ".repeat(caretCol)).append('↑');
        return sb.toString();
    }

    /**
     * 生成多行源码的上下文片段。
     * 展示出错行及其上下各一行（如果存在），并在出错行标记 {@code ↑}。
     *
     * @param source     完整多行源码文本
     * @param charOffset 出错字符的 0-based 全局偏移
     * @return 多行上下文字符串
     */
    public static String snippetMultiLine(String source, int charOffset) {
        if (source == null || source.isEmpty()) return null;
        charOffset = Math.max(0, Math.min(charOffset, source.length()));

        String[] lines = source.split("\n", -1);
        int lineIdx = 0;
        int colIdx = 0;
        int remaining = charOffset;

        for (int i = 0; i < lines.length; i++) {
            int lineLen = lines[i].length() + 1; // +1 for \n
            if (remaining < lineLen || i == lines.length - 1) {
                lineIdx = i;
                colIdx = remaining;
                break;
            }
            remaining -= lineLen;
        }

        StringBuilder sb = new StringBuilder();
        int lineNumWidth = String.valueOf(lineIdx + 2).length(); // +2 因为是 1-based 且可能有下一行

        // 上一行（如果存在）
        if (lineIdx > 0) {
            appendLineNum(sb, lineIdx, lineNumWidth);
            sb.append(lines[lineIdx - 1]).append('\n');
        }

        // 出错行
        appendLineNum(sb, lineIdx + 1, lineNumWidth);
        sb.append(lines[lineIdx]).append('\n');

        // 指示符行
        int padding = lineNumWidth + 3 + colIdx; // " | " = 3 chars
        sb.append(" ".repeat(padding)).append('↑').append('\n');

        // 下一行（如果存在）
        if (lineIdx + 1 < lines.length) {
            appendLineNum(sb, lineIdx + 2, lineNumWidth);
            sb.append(lines[lineIdx + 1]);
        }

        return sb.toString();
    }

    private static void appendLineNum(StringBuilder sb, int num, int width) {
        String numStr = String.valueOf(num);
        sb.append(" ".repeat(Math.max(0, width - numStr.length())))
          .append(numStr).append(" | ");
    }

    // ======================== YAML 伪片段 ========================

    /** 紧凑 YAML 片段最大键值对数量。 */
    private static final int MAX_YAML_ENTRIES = 8;

    /**
     * 从 Map 生成紧凑的伪 YAML 片段，用于脚本解析阶段的诊断输出。
     * <p>
     * 输入为任意 {@code Map<String, Object>}（与序列化格式无关），
     * 输出格式采用伪 YAML 风格（{@code key: value}）方便人工阅读。
     * 以双下划线（{@code __}）开头的内部 key 自动跳过。
     *
     * <pre>
     *   action: setHp
     *   args: [100, 200]
     *   store: result
     * </pre>
     *
     * @param map 待格式化的属性映射（可为 {@code null} 或空）
     * @return 紧凑的伪 YAML 字符串，{@code null} 表示无可用内容
     */
    public static String mapSnippet(Map<String, Object> map) {
        if (map == null || map.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = entry.getKey();
            // 内部属性不显示
            if (key.startsWith("__")) continue;
            if (count >= MAX_YAML_ENTRIES) {
                sb.append("  ...(").append(map.size() - count).append(" more)");
                break;
            }
            if (sb.length() > 0) sb.append('\n');
            sb.append("  ").append(key).append(": ").append(formatYamlValue(entry.getValue()));
            count++;
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /**
     * 从 FlowNode 的 attrs 生成伪 YAML 片段（供 emit/validate 阶段使用）。
     * 过滤掉内部属性（{@code def}、以 {@code _} 开头的键）和大型对象。
     *
     * @param attrs 节点属性映射
     * @return 紧凑的伪 YAML 字符串，{@code null} 表示无可用内容
     */
    public static String nodeSnippet(Map<String, Object> attrs) {
        if (attrs == null || attrs.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (Map.Entry<String, Object> entry : attrs.entrySet()) {
            String key = entry.getKey();
            // 过滤内部属性
            if (key.startsWith("_") || "def".equals(key) || "mathNode".equals(key)
                    || "valueNode".equals(key) || "conditionAction".equals(key)) continue;
            if (count >= MAX_YAML_ENTRIES) {
                sb.append("  ...(").append(attrs.size() - count).append(" more)");
                break;
            }
            if (sb.length() > 0) sb.append('\n');
            sb.append("  ").append(key).append(": ").append(formatYamlValue(entry.getValue()));
            count++;
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /**
     * 将值格式化为 YAML 友好显示。
     * 列表显示为 {@code [a, b, c]}，嵌套 Map 显示为 {@code \{...\}}。
     */
    private static String formatYamlValue(Object value) {
        if (value == null) return "null";
        if (value instanceof String s) {
            return s.length() > 60 ? "\"" + s.substring(0, 57) + "...\"" : s;
        }
        if (value instanceof Collection<?> list) {
            if (list.size() > 6) return "[" + list.stream().limit(4)
                    .map(Object::toString).reduce((a, b) -> a + ", " + b).orElse("") + ", ...(" + list.size() + " total)]";
            return list.toString();
        }
        if (value instanceof Map<?, ?>) return "{...}";
        return String.valueOf(value);
    }
}
