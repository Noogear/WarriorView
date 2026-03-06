package cn.warriorview.script.diagnostic;

/**
 * 单条诊断信息。
 *
 * <p>包含位置信息、错误类别、消息文本以及可选的源码上下文片段和指示符。
 * 设计为不可变值对象，可安全跨线程传递。
 *
 * @param location 源码位置
 * @param category 错误类别
 * @param message  简短错误描述
 * @param snippet  格式化后的源码上下文片段（含 {@code ↑} 指示符），可为 {@code null}
 */
public record Diagnostic(
        SourceLocation location,
        DiagnosticCategory category,
        String message,
        String snippet) {

    /**
     * 不带源码片段的快捷构造。
     */
    public Diagnostic(SourceLocation location, DiagnosticCategory category, String message) {
        this(location, category, message, null);
    }

    /**
     * 带源码片段的快捷工厂。
     * 自动从 {@code source} 文本和偏移量生成上下文片段。
     *
     * @param sourceText 完整源码文本
     * @param offset     出错字符的 0-based 偏移
     * @param category   错误类别
     * @param message    错误消息
     * @return 含上下文片段的 Diagnostic
     */
    public static Diagnostic of(String sourceText, int offset, DiagnosticCategory category, String message) {
        String sourceName = truncateSource(sourceText, 40);
        SourceLocation loc = SourceLocation.ofOffset(sourceName, offset);
        String snippet = SourceView.snippet(sourceText, offset);
        return new Diagnostic(loc, category, message, snippet);
    }

    /**
     * 带源码片段的多行工厂。
     *
     * @param sourceName 来源标识
     * @param sourceText 完整源码文本
     * @param offset     出错字符的 0-based 偏移
     * @param category   错误类别
     * @param message    错误消息
     */
    public static Diagnostic ofMultiLine(String sourceName, String sourceText, int offset,
                                         DiagnosticCategory category, String message) {
        SourceLocation loc = SourceLocation.ofMultiLine(sourceName, sourceText, offset);
        String snippet = SourceView.snippetMultiLine(sourceText, offset);
        return new Diagnostic(loc, category, message, snippet);
    }

    /**
     * 无位置信息的简节点诊断（常用于脚本验证阶段已有行号的场景）。
     */
    public static Diagnostic simple(SourceLocation location, DiagnosticCategory category, String message) {
        return new Diagnostic(location, category, message, null);
    }

    /**
     * 格式化为人类可读的多行诊断文本。
     *
     * <pre>
     * [Parse] at expr:1:15 — Expected ')' in function call
     *   {hp} / max(100, + 5
     *                 ↑
     * </pre>
     */
    public String format() {
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(category.label()).append("] ");
        if (location != null && location != SourceLocation.UNKNOWN) {
            sb.append("at ").append(location).append(" — ");
        }
        sb.append(message);
        if (snippet != null) {
            sb.append('\n').append(snippet);
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return format();
    }

    // ── 内部工具 ──

    private static String truncateSource(String text, int maxLen) {
        if (text == null) return "<unknown>";
        String single = text.replace('\n', ' ').replace('\r', ' ');
        return single.length() <= maxLen ? single : single.substring(0, maxLen) + "...";
    }
}
