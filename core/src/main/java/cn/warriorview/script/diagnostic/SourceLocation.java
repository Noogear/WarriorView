package cn.warriorview.script.diagnostic;

/**
 * 源码位置描述符（不可变值对象）。
 *
 * @param source 来源标识（文件名、表达式字符串、脚本 ID 等）
 * @param line   行号（1-based；单行文本固定为 1）
 * @param column 列号（1-based）
 */
public record SourceLocation(String source, int line, int column) {

    /** 未知位置占位。 */
    public static final SourceLocation UNKNOWN = new SourceLocation("<unknown>", 0, 0);

    /**
     * 从单行表达式字符串 + 字符偏移量推算位置。
     * 适用于 MathParser 等单行解析场景。
     *
     * @param source 来源标识（通常为表达式原文的前 40 字符截断）
     * @param offset 从 0 开始的字符偏移量
     */
    public static SourceLocation ofOffset(String source, int offset) {
        return new SourceLocation(source, 1, offset + 1);
    }

    /**
     * 从多行文本 + 字符偏移量推算行/列。
     * 适用于 YAML 脚本等多行源码。
     *
     * @param source     来源标识
     * @param fullText   完整源码文本
     * @param charOffset 从 0 开始的全局字符偏移
     */
    public static SourceLocation ofMultiLine(String source, String fullText, int charOffset) {
        int line = 1;
        int col = 1;
        int limit = Math.min(charOffset, fullText.length());
        for (int i = 0; i < limit; i++) {
            if (fullText.charAt(i) == '\n') {
                line++;
                col = 1;
            } else {
                col++;
            }
        }
        return new SourceLocation(source, line, col);
    }

    @Override
    public String toString() {
        if (this == UNKNOWN) return "<unknown>";
        if (line == 0) return source;
        return source + ":" + line + ":" + column;
    }
}
