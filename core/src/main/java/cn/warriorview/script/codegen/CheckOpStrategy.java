package cn.warriorview.script.codegen;

import cn.warriorview.script.core.CheckOp;
import cn.warriorview.script.core.CompilationContext;
import cn.warriorview.script.core.ScriptIR.FlowNode;
import cn.warriorview.script.core.ScriptIR.IRType;
import org.objectweb.asm.MethodVisitor;

/**
 * CHECK 操作符的字节码发射策略接口。
 * <p>
 * 每个 {@link CheckOp} 枚举值对应一个策略实现，负责将该操作符的语义转换为 JVM 字节码。
 * 策略实现统一注册在 {@link CheckOpEmitters} 中，通过 {@link CheckOpEmitters#forOp(CheckOp)} 获取。
 * <p>
 * 新增操作符时只需：1）在 {@link CheckOp} 添加枚举值；2）在 {@link CheckOpEmitters} 注册策略。
 * 无需修改 {@code CheckNodeHandler} 的核心控制流。
 *
 * @see CheckOpEmitters
 * @see CheckOp
 */
@FunctionalInterface
public interface CheckOpStrategy {

    /**
     * 发射操作符的条件检查字节码。
     * <p>
     * 调用时变量已定位到 slot，调用者负责根据返回的 jumpOp 发射条件跳转。
     *
     * @param mv   当前方法的 MethodVisitor
     * @param op   操作符枚举值（STARTS_WITH/ENDS_WITH 共享策略时需区分）
     * @param slot 被检查变量的本地变量槽位
     * @param type 变量的 IR 类型
     * @param node CHECK FlowNode（含 value、valueList 等属性）
     * @param ctx  编译上下文
     * @return "条件成立时应跳转"的 JVM 条件跳转 opcode
     */
    int emit(MethodVisitor mv, CheckOp op, int slot, IRType type, FlowNode node, CompilationContext ctx);
}
