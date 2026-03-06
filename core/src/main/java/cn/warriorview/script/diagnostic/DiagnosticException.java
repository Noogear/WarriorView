package cn.warriorview.script.diagnostic;

/**
 * 诊断异常——携带结构化 {@link Diagnostic} 的运行时异常。
 *
 * <p>统一替代原有的裸 {@code IllegalArgumentException} 和 {@code ScriptCompileException}，
 * 使所有编译期错误都带有位置信息、源码片段和错误类别。
 *
 * <p>继承 {@link RuntimeException} 以保持与现有 API 契约（不强制 checked）兼容。
 */
public class DiagnosticException extends RuntimeException {

    private final Diagnostic diagnostic;

    public DiagnosticException(Diagnostic diagnostic) {
        super(diagnostic.format());
        this.diagnostic = diagnostic;
    }

    public DiagnosticException(Diagnostic diagnostic, Throwable cause) {
        super(diagnostic.format(), cause);
        this.diagnostic = diagnostic;
    }

    /** 获取结构化诊断信息。 */
    public Diagnostic diagnostic() {
        return diagnostic;
    }

    /** 获取诊断类别。 */
    public DiagnosticCategory category() {
        return diagnostic.category();
    }

    /** 获取源码位置。 */
    public SourceLocation location() {
        return diagnostic.location();
    }
}
