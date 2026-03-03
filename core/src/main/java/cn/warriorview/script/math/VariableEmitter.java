package cn.warriorview.script.math;

import org.objectweb.asm.MethodVisitor;

/**
 * 变量加载策略接口，用于解耦 {@link MathNodeEmitter} 中的变量值压栈逻辑。
 *
 * <p>两种实现由 {@link MathNodeEmitter} 工厂方法提供：
 * <ul>
 * <li>{@link MathNodeEmitter#slotBased(int[])} — MathEngine 路径</li>
 * <li>{@link MathNodeEmitter#contextBased(cn.warriorview.script.core.CompilationContext)} — 脚本 IR 路径</li>
 * </ul>
 */
@FunctionalInterface
interface VariableEmitter {
    /**
     * 将指定变量的 double 值压到操作数栈顶。
     *
     * @param var 变量节点（含名称与可选下标）
     * @param mv  当前方法字节码访问器
     */
    void emit(MathNode.VariableNode var, MethodVisitor mv);
}
