package cn.warriorview.script.diagnostic;

/**
 * 诊断类别，用于标识错误来源阶段。
 */
public enum DiagnosticCategory {

    /** 解析阶段错误（词法/语法错误）。 */
    PARSE("Parse"),

    /** 类型阶段错误（类型不匹配、窄化失败）。 */
    TYPE("Type"),

    /** 语义阶段错误（未定义变量、参数数目不匹配、重复声明等）。 */
    SEMANTIC("Semantic");

    private final String label;

    DiagnosticCategory(String label) {
        this.label = label;
    }

    /** 显示用短标签。 */
    public String label() {
        return label;
    }
}
