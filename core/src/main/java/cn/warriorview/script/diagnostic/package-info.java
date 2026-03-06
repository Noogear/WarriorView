/**
 * 统一诊断模块——为 script 和 math 子系统提供结构化的编译期错误报告。
 *
 * <h2>核心类型</h2>
 * <ul>
 * <li>{@link cn.warriorview.script.diagnostic.SourceLocation} — 文件名 + 行号 + 列号</li>
 * <li>{@link cn.warriorview.script.diagnostic.DiagnosticCategory} — 错误类别（Parse/Type/Semantic）</li>
 * <li>{@link cn.warriorview.script.diagnostic.Diagnostic} — 不可变诊断记录（位置 + 类别 + 消息 + 源码片段）</li>
 * <li>{@link cn.warriorview.script.diagnostic.SourceView} — 源码上下文片段生成器（含 ↑ 指示符）</li>
 * <li>{@link cn.warriorview.script.diagnostic.DiagnosticException} — 携带 Diagnostic 的运行时异常</li>
 * </ul>
 */
package cn.warriorview.script.diagnostic;
