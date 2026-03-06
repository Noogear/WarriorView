package cn.warriorview.script.core;

import cn.warriorview.script.diagnostic.Diagnostic;
import cn.warriorview.script.diagnostic.DiagnosticCategory;
import cn.warriorview.script.diagnostic.DiagnosticException;
import cn.warriorview.script.diagnostic.SourceLocation;
import cn.warriorview.script.diagnostic.SourceView;

import java.util.Map;

/**
 * 脚本编译异常。
 * 当出现语法、类型或结构错误时抛出，通常会包含纯英文的友好多行提示。
 *
 * <p>继承自 {@link DiagnosticException}，使脚本编译错误也纳入统一诊断体系，
 * 可通过 {@link #diagnostic()} 获取结构化的位置信息和错误类别。
 *
 * <h3>工厂方法选择指南</h3>
 * <table>
 *   <tr><th>阶段</th><th>方法</th><th>说明</th></tr>
 *   <tr><td>parse</td><td>{@link #parse(String)}</td><td>解析阶段错误，无 YAML 上下文（UNKNOWN 位置）</td></tr>
 *   <tr><td>parse</td><td>{@link #parse(Map, String)}</td><td>解析阶段错误，附带 YAML 片段</td></tr>
 *   <tr><td>emit</td><td>{@link #create(ScriptIR.FlowNode, String)}</td><td>语义错误，从节点提取行号</td></tr>
 *   <tr><td>emit</td><td>{@link #create(String, ScriptIR.FlowNode, String)}</td><td>语义错误，额外携带脚本 ID</td></tr>
 *   <tr><td>validate</td><td>{@link #type(ScriptIR.FlowNode, String)}</td><td>类型验证错误，从节点提取行号</td></tr>
 *   <tr><td>any</td><td>{@link #create(String, ScriptIR.FlowNode, DiagnosticCategory, String)}</td><td>完全指定脚本 ID + 类别</td></tr>
 * </table>
 */
public class ScriptCompileException extends DiagnosticException {

    public ScriptCompileException(Diagnostic diagnostic) {
        super(diagnostic);
    }

    public ScriptCompileException(Diagnostic diagnostic, Throwable cause) {
        super(diagnostic, cause);
    }

    // ======================== 工厂方法 — parse 阶段 ========================

    /**
     * 解析阶段错误（handler.parse() 中使用，此时尚无 FlowNode）。
     * 类别自动标记为 {@link DiagnosticCategory#PARSE}。
     *
     * @param message 错误描述
     */
    public static ScriptCompileException parse(String message) {
        return new ScriptCompileException(
                Diagnostic.simple(SourceLocation.UNKNOWN, DiagnosticCategory.PARSE, message));
    }

    /**
     * 解析阶段错误，附带原始 YAML 映射作为上下文片段。
     * <p>
     * 生成的诊断信息会包含一个紧凑的伪 YAML 代码片段，帮助用户快速定位问题来源。
     *
     * <pre>
     * [Parse] at mobs/boss.yml:7:0 — MATH node requires 'store' and 'expr' fields.
     *   expr: {hp} + 10
     * </pre>
     *
     * @param attrs   节点属性映射
     * @param message 错误描述
     */
    public static ScriptCompileException parse(Map<String, Object> attrs, String message) {
        String snippet = SourceView.mapSnippet(attrs);
        return new ScriptCompileException(
                new Diagnostic(SourceLocation.UNKNOWN, DiagnosticCategory.PARSE, message, snippet));
    }

    // ======================== 工厂方法 — emit/validate 阶段 ========================

    /**
     * 语义错误（emit/validate 阶段，从节点提取行号）。
     * 类别自动标记为 {@link DiagnosticCategory#SEMANTIC}。
     *
     * @param node    出错节点（可为 null）
     * @param message 错误描述
     */
    public static ScriptCompileException create(ScriptIR.FlowNode node, String message) {
        return create(null, node, DiagnosticCategory.SEMANTIC, message);
    }

    /**
     * 语义错误，额外携带脚本 ID 用于来源标识。
     *
     * @param scriptId 脚本标识（可为 null，将降级为 {@code &lt;script&gt;}）
     * @param node     出错节点（可为 null）
     * @param message  错误描述
     */
    public static ScriptCompileException create(String scriptId, ScriptIR.FlowNode node, String message) {
        return create(scriptId, node, DiagnosticCategory.SEMANTIC, message);
    }

    /**
     * 类型验证错误（validateTypes 阶段，从节点提取行号）。
     * 类别自动标记为 {@link DiagnosticCategory#TYPE}。
     *
     * @param node    出错节点（可为 null）
     * @param message 错误描述
     */
    public static ScriptCompileException type(ScriptIR.FlowNode node, String message) {
        return create(null, node, DiagnosticCategory.TYPE, message);
    }

    /**
     * 通用工厂方法：指定自定义类别，从节点提取行号及属性片段。
     *
     * @param node     出错节点（可为 null）
     * @param category 诊断类别
     * @param message  错误描述
     */
    public static ScriptCompileException create(ScriptIR.FlowNode node, DiagnosticCategory category, String message) {
        return create(null, node, category, message);
    }

    /**
     * 最完整的工厂方法：脚本 ID + 节点 + 自定义类别。
     * <p>
     * 构建逻辑：
     * <ol>
     *   <li>从节点提取行号（{@code __line__} 属性）→ {@link SourceLocation}</li>
     *   <li>使用脚本 ID 作为 source 标识（默认 {@code &lt;script&gt;}）</li>
     *   <li>从节点 attrs 生成伪 YAML 片段（{@link SourceView#nodeSnippet}）→ snippet</li>
     * </ol>
     *
     * @param scriptId 脚本标识（可为 null）
     * @param node     出错节点（可为 null）
     * @param category 诊断类别
     * @param message  错误描述
     */
    public static ScriptCompileException create(String scriptId, ScriptIR.FlowNode node,
                                                DiagnosticCategory category, String message) {
        String source = scriptId != null ? scriptId : "<script>";
        SourceLocation loc;
        String snippet = null;

        if (node != null) {
            int line = node.getLineNumber();
            loc = line > 0
                    ? new SourceLocation(source, line, 0)
                    : new SourceLocation(source, 0, 0);
            snippet = SourceView.nodeSnippet(node.attrs());
        } else {
            loc = scriptId != null
                    ? new SourceLocation(source, 0, 0)
                    : SourceLocation.UNKNOWN;
        }
        return new ScriptCompileException(new Diagnostic(loc, category, message, snippet));
    }
}
