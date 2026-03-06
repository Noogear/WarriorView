package cn.warriorview.script.core;

import cn.warriorview.script.diagnostic.Diagnostic;
import cn.warriorview.script.diagnostic.DiagnosticCategory;
import cn.warriorview.script.diagnostic.SourceLocation;
import cn.warriorview.script.diagnostic.SourceView;

import java.util.Map;
import java.util.Set;

/**
 * 节点解析上下文，封装节点属性 Map 与脚本来源信息。
 *
 * <p>作为 {@link ScriptIR.FlowNodeHandler#parse} 的唯一入参，同时承担两项职责：
 * <ol>
 *   <li><b>数据访问</b>：通过 {@link #get}、{@link #getOrDefault}、{@link #containsKey} 读取节点属性，
 *       无需在 handler 中持有原始 Map。</li>
 *   <li><b>诊断上下文</b>：通过 {@link #error} 生成带精确位置（文件名 + 行号）的
 *       {@link ScriptCompileException}，消除了以往需要将整个 {@code attrs} Map 传给
 *       {@code SCE.parse(attrs, msg)} 的冗余，也无需在外层包一层 try-catch 来补充 scriptId。</li>
 * </ol>
 *
 * <h3>创建方式</h3>
 * 通常由 {@link cn.warriorview.script.parser.ScriptParser} 在调用 handler 前构建；
 * handler 内部若需要递归解析子节点，调用 {@link #withAttrs} 派生子上下文以继承 scriptId。
 *
 * <h3>线程安全</h3>
 * 不可变（{@code attrs} 引用不变），线程安全。
 */
public final class ParseContext {

    private final Map<String, Object> attrs;
    private final String scriptId;

    public ParseContext(Map<String, Object> attrs, String scriptId) {
        this.attrs = attrs;
        this.scriptId = scriptId;
    }

    // ======================== 属性访问 ========================

    /**
     * 读取属性值；不存在时返回 {@code null}。
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        return (T) attrs.get(key);
    }

    /**
     * 读取属性值；不存在时返回 {@code defaultValue}。
     */
    @SuppressWarnings("unchecked")
    public <T> T getOrDefault(String key, T defaultValue) {
        return (T) attrs.getOrDefault(key, defaultValue);
    }

    public boolean containsKey(String key) {
        return attrs.containsKey(key);
    }

    public Set<Map.Entry<String, Object>> entrySet() {
        return attrs.entrySet();
    }

    /**
     * 返回原始属性 Map，供需要整体传递的场景（如 {@link SourceView#mapSnippet}）。
     */
    public Map<String, Object> attrs() {
        return attrs;
    }

    /** 脚本来源标识（文件名等），可为 {@code null}。 */
    public String scriptId() {
        return scriptId;
    }

    // ======================== 子上下文 ========================

    /**
     * 派生一个携带不同属性 Map 但相同 {@code scriptId} 的子上下文。
     * <p>用于在 handler 内部递归解析子节点时传播来源信息。
     */
    public ParseContext withAttrs(Map<String, Object> newAttrs) {
        return new ParseContext(newAttrs, scriptId);
    }

    // ======================== 诊断帮助 ========================

    /**
     * 创建 parse 阶段编译错误。
     * <p>
     * 自动完成以下工作：
     * <ul>
     *   <li>从 {@code attrs} 生成紧凑的伪 YAML 代码片段；</li>
     *   <li>从 {@code __line__} 属性提取行号；</li>
     *   <li>若 {@code scriptId} 不为 {@code null}，将文件名 + 行号写入 {@link SourceLocation}。</li>
     * </ul>
     *
     * <pre>
     * [Parse] at mobs/boss.yml:7:0 — MATH node requires 'store' and 'expr' fields.
     *   expr: {hp} + 10
     * </pre>
     *
     * @param message 错误描述（纯英文，供日志/控制台输出）
     */
    public ScriptCompileException error(String message) {
        int line = 0;
        Object lineObj = attrs.get("__line__");
        if (lineObj instanceof Integer i) line = i;
        else if (lineObj instanceof Number n) line = n.intValue();

        SourceLocation loc = (scriptId != null)
                ? new SourceLocation(scriptId, line, 0)
                : SourceLocation.UNKNOWN;

        String snippet = SourceView.mapSnippet(attrs);
        return new ScriptCompileException(
                new Diagnostic(loc, DiagnosticCategory.PARSE, message, snippet));
    }
}
