package cn.warriorview.script.math;

import cn.warriorview.script.codegen.ASMUtils;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * 统一的 {@link MathNode} → JVM 字节码发射器。
 *
 * <p>消除了 {@code MathEngine.emitNode} 与 {@code MathNodeHandler.emitMathNode}
 * 之间的重复树遍历逻辑。调用方通过 {@link VariableEmitter} 策略接口注入变量加载方式：
 * <ul>
 * <li>{@link #slotBased(int[])} — MathEngine 路径：从 double 参数槽或 double[] 数组加载</li>
 * <li>{@link VariableEmitter} 实现由 script 模块的 {@code CompilationContext#toVariableEmitter()} 提供 —
 *     按名称解析槽位，支持 int/long→double 提升</li>
 * </ul>
 */
public final class MathNodeEmitter {

    private MathNodeEmitter() {
    }

    // ======================== 策略工厂 ========================

    /**
     * MathEngine 专用策略：根据 {@code varSlots[i]} 决定加载方式。
     * <ul>
     * <li>{@code >= 0} → {@code DLOAD slot}（特化模式或已缓存变量）</li>
     * <li>{@code -1}   → {@code ALOAD 1; iconst i; DALOAD}（double[] 数组模式）</li>
     * </ul>
     */
    static VariableEmitter slotBased(int[] varSlots) {
        return (var, mv) -> {
            int slot = varSlots[var.index()];
            if (slot >= 0) {
                mv.visitVarInsn(Opcodes.DLOAD, slot);
            } else {
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                ASMUtils.emitIntConst(mv, var.index());
                mv.visitInsn(Opcodes.DALOAD);
            }
        };
    }

    // ======================== 统一树遍历 ========================

    /**
     * 递归将 {@link MathNode} 发射为 JVM 字节码。
     *
     * <p>包含以下内联优化：
     * <ul>
     * <li><b>x^2</b>：{@code emit(x); DUP2; DMUL}</li>
     * <li><b>x^3</b>：{@code emit(x); DUP2; DUP2; DMUL; DMUL}</li>
     * <li><b>x^4</b>：{@code emit(x); DUP2; DMUL; DUP2; DMUL}</li>
     * <li>字面量通过 {@link ASMUtils#emitDoubleConst} 发射，优先使用 {@code DCONST_0/1}</li>
     * </ul>
     *
     * @param node       当前 AST 节点
     * @param mv         目标 MethodVisitor
     * @param varEmitter 变量加载策略（由调用方注入）
     */
    public static void emit(MathNode node, MethodVisitor mv, VariableEmitter varEmitter) {
        switch (node) {
            case MathNode.LiteralNode lit ->
                    // 使用 ASMUtils 确保 0.0/1.0 走 DCONST_0/DCONST_1，其余用 LDC
                    ASMUtils.emitDoubleConst(mv, lit.value());

            case MathNode.VariableNode v -> varEmitter.emit(v, mv);

            case MathNode.UnaryNode u -> {
                emit(u.operand(), mv, varEmitter);
                if (u.isNegation()) mv.visitInsn(Opcodes.DNEG);
            }

            case MathNode.BinaryNode b -> emitBinary(b, mv, varEmitter);

            case MathNode.FunctionNode f -> {
                for (MathNode arg : f.arguments()) emit(arg, mv, varEmitter);
                f.function().emit(mv, f.arguments().size());
            }

            case MathNode.TernaryNode t -> {
                // condition ? trueExpr : falseExpr
                // 短路语义：仅评估选中的分支
                Label falseLabel = new Label();
                Label endLabel = new Label();
                emit(t.condition(), mv, varEmitter);
                mv.visitInsn(Opcodes.DCONST_0);
                mv.visitInsn(Opcodes.DCMPL);          // 0 iff condition==0.0
                mv.visitJumpInsn(Opcodes.IFEQ, falseLabel);
                emit(t.trueExpr(), mv, varEmitter);
                mv.visitJumpInsn(Opcodes.GOTO, endLabel);
                mv.visitLabel(falseLabel);
                emit(t.falseExpr(), mv, varEmitter);
                mv.visitLabel(endLabel);
            }

            case MathNode.CustomFunctionNode cf -> {
                // 先压入函数名常量，再压入参数
                mv.visitLdcInsn(cf.name());
                int argCount = cf.arguments().size();
                if (argCount <= 3) {
                    // 特化路径：参数直接传递，无数组分配
                    for (MathNode arg : cf.arguments()) emit(arg, mv, varEmitter);
                } else {
                    // 通用路径：打包参数到 double[]
                    ASMUtils.emitIntConst(mv, argCount);
                    mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_DOUBLE);
                    for (int i = 0; i < argCount; i++) {
                        mv.visitInsn(Opcodes.DUP);
                        ASMUtils.emitIntConst(mv, i);
                        emit(cf.arguments().get(i), mv, varEmitter);
                        mv.visitInsn(Opcodes.DASTORE);
                    }
                }
                MathFunction.emitCustom(mv, cf.name(), argCount);
            }
        }
    }

    // ── 幂整数特化 ────────────────────────────────────────────────────────────

    private static void emitBinary(MathNode.BinaryNode b, MethodVisitor mv, VariableEmitter varEmitter) {
        // 幂整数特化：x^2/3/4 展开为 DUP2+DMUL 链，绕过 Math.pow 的 exp/log 路径。
        // HotSpot 仅对 pow(x,2.0) 做 intrinsic (→ DMUL)；x^3 用 DUP2 约 5 ns，
        // x^4=(x^2)^2 用两次 DUP2;DMUL 约 2.6 ns，均优于 Math.pow 的 ~10 ns。
        if (b.op() == Operator.POWER && b.right() instanceof MathNode.LiteralNode rlit) {
            double exp = rlit.value();
            if (exp == 2.0) {
                emit(b.left(), mv, varEmitter);
                mv.visitInsn(Opcodes.DUP2);
                mv.visitInsn(Opcodes.DMUL);
                return;
            } else if (exp == 3.0) {
                emit(b.left(), mv, varEmitter);
                mv.visitInsn(Opcodes.DUP2);
                mv.visitInsn(Opcodes.DUP2);
                mv.visitInsn(Opcodes.DMUL);
                mv.visitInsn(Opcodes.DMUL);
                return;
            } else if (exp == 4.0) {
                emit(b.left(), mv, varEmitter);
                mv.visitInsn(Opcodes.DUP2);
                mv.visitInsn(Opcodes.DMUL);
                mv.visitInsn(Opcodes.DUP2);
                mv.visitInsn(Opcodes.DMUL);
                return;
            }
        }
        // 短路 AND：若左操作数为假（0.0）则跳过右操作数
        if (b.op() == Operator.AND) {
            Label falseLabel = new Label();
            Label endLabel = new Label();
            emit(b.left(), mv, varEmitter);
            mv.visitInsn(Opcodes.DCONST_0);
            mv.visitInsn(Opcodes.DCMPL);          // 0 iff left==0.0
            mv.visitJumpInsn(Opcodes.IFEQ, falseLabel);
            emit(b.right(), mv, varEmitter);
            mv.visitInsn(Opcodes.DCONST_0);
            mv.visitInsn(Opcodes.DCMPL);          // 0 iff right==0.0
            mv.visitJumpInsn(Opcodes.IFEQ, falseLabel);
            mv.visitInsn(Opcodes.DCONST_1);
            mv.visitJumpInsn(Opcodes.GOTO, endLabel);
            mv.visitLabel(falseLabel);
            mv.visitInsn(Opcodes.DCONST_0);
            mv.visitLabel(endLabel);
            return;
        }
        // 短路 OR：若左操作数为真（非 0.0）则跳过右操作数
        if (b.op() == Operator.OR) {
            Label trueLabel = new Label();
            Label endLabel = new Label();
            emit(b.left(), mv, varEmitter);
            mv.visitInsn(Opcodes.DCONST_0);
            mv.visitInsn(Opcodes.DCMPL);          // != 0 iff left!=0.0
            mv.visitJumpInsn(Opcodes.IFNE, trueLabel);
            emit(b.right(), mv, varEmitter);
            mv.visitInsn(Opcodes.DCONST_0);
            mv.visitInsn(Opcodes.DCMPL);          // != 0 iff right!=0.0
            mv.visitJumpInsn(Opcodes.IFNE, trueLabel);
            mv.visitInsn(Opcodes.DCONST_0);
            mv.visitJumpInsn(Opcodes.GOTO, endLabel);
            mv.visitLabel(trueLabel);
            mv.visitInsn(Opcodes.DCONST_1);
            mv.visitLabel(endLabel);
            return;
        }
        // 通用路径
        emit(b.left(), mv, varEmitter);
        emit(b.right(), mv, varEmitter);
        b.op().emit(mv);
    }

    // ── 比较/布尔运算符辅助（步骤 4 用） ──────────────────────────────────────

    /**
     * 发射比较运算符的字节码序列（结果为 0.0 或 1.0）：
     * <pre>
     *   DCMPL
     *   IF_<cond>  false_label
     *   DCONST_1
     *   GOTO       end_label
     * false_label:
     *   DCONST_0
     * end_label:
     * </pre>
     *
     * @param trueJumpOpcode 条件为真时跳转的 opcode（如 {@link Opcodes#IFGT}）
     */
    static void emitCompare(MethodVisitor mv, int trueJumpOpcode) {
        mv.visitInsn(Opcodes.DCMPL);
        Label falseLabel = new Label();
        Label endLabel = new Label();
        // 条件不满足时跳到 falseLabel
        mv.visitJumpInsn(ASMUtils.invertJump(trueJumpOpcode), falseLabel);
        mv.visitInsn(Opcodes.DCONST_1);
        mv.visitJumpInsn(Opcodes.GOTO, endLabel);
        mv.visitLabel(falseLabel);
        mv.visitInsn(Opcodes.DCONST_0);
        mv.visitLabel(endLabel);
    }
}
