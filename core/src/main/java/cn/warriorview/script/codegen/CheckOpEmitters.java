package cn.warriorview.script.codegen;

import cn.warriorview.script.core.CheckOp;
import cn.warriorview.script.core.CompilationContext;
import cn.warriorview.script.core.ScriptIR.FlowNode;
import cn.warriorview.script.core.ScriptIR.IRType;
import cn.warriorview.script.math.MathNode;
import cn.warriorview.script.math.MathNodeEmitter;
import com.google.common.collect.ImmutableList;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.EnumMap;

/**
 * 全部 {@link CheckOp} 操作符的字节码发射策略实现 + 注册表。
 * <p>
 * 从 {@code CheckNodeHandler} 中提取的纯字节码生成逻辑，消除 Handler 对具体发射细节的耦合。
 * 新增操作符只需在 {@link #STRATEGIES} 注册对应函数引用，无需修改 Handler 的控制流。
 *
 * @see CheckOp
 */
public final class CheckOpEmitters {

    private CheckOpEmitters() {}

    // ======================== 策略接口 ========================

    /**
     * CHECK 操作符的字节码发射策略。
     * <p>
     * 每个 {@link CheckOp} 枚举值对应一个策略实现，负责将该操作符的语义转换为 JVM 字节码。
     * 新增操作符时只需：1）在 {@link CheckOp} 添加枚举值；2）在 {@link CheckOpEmitters} 注册策略。
     */
    @FunctionalInterface
    public interface Strategy {

        /**
         * 发射操作符的条件检查字节码。
         *
         * @param mv   当前方法的 MethodVisitor
         * @param op   操作符枚举值
         * @param slot 被检查变量的本地变量槽位
         * @param type 变量的 IR 类型
         * @param node CHECK FlowNode（含 value、valueList 等属性）
         * @param ctx  编译上下文
         * @return "条件成立时应跳转"的 JVM 条件跳转 opcode
         */
        int emit(MethodVisitor mv, CheckOp op, int slot, IRType type, FlowNode node, CompilationContext ctx);
    }

    // ======================== 策略注册表 ========================

    private static final EnumMap<CheckOp, Strategy> STRATEGIES = new EnumMap<>(CheckOp.class);

    // 共享策略实例（避免方法引用每次创建不同 lambda，便于身份比较和调试）
    private static final Strategy STRING_OP_STRATEGY = CheckOpEmitters::emitStringOp;
    private static final Strategy COMPARISON_STRATEGY = CheckOpEmitters::emitComparison;

    static {
        STRATEGIES.put(CheckOp.NULL,        (mv, op, slot, type, node, ctx) -> emitNullCheck(mv, slot));
        STRATEGIES.put(CheckOp.INSTANCEOF,  (mv, op, slot, type, node, ctx) -> emitInstanceof(mv, slot, node));
        STRATEGIES.put(CheckOp.CONTAINS,    CheckOpEmitters::emitContains);
        STRATEGIES.put(CheckOp.STARTS_WITH, STRING_OP_STRATEGY);
        STRATEGIES.put(CheckOp.ENDS_WITH,   STRING_OP_STRATEGY);
        STRATEGIES.put(CheckOp.MATCHES,     (mv, op, slot, type, node, ctx) -> emitMatches(mv, slot, node));
        STRATEGIES.put(CheckOp.IN,          CheckOpEmitters::emitIn);
        STRATEGIES.put(CheckOp.BETWEEN,     CheckOpEmitters::emitBetween);
        // 数值/相等操作符共享比较策略
        for (CheckOp cop : new CheckOp[]{CheckOp.EQ, CheckOp.NEQ, CheckOp.GT, CheckOp.GTE, CheckOp.LT, CheckOp.LTE}) {
            STRATEGIES.put(cop, COMPARISON_STRATEGY);
        }
    }

    /**
     * 获取指定操作符的发射策略。
     *
     * @throws IllegalStateException 若操作符无注册策略
     */
    public static Strategy forOp(CheckOp op) {
        Strategy s = STRATEGIES.get(op);
        if (s == null) {
            throw new IllegalStateException("No emission strategy registered for: " + op);
        }
        return s;
    }

    // ======================== null ========================

    private static int emitNullCheck(MethodVisitor mv, int slot) {
        mv.visitVarInsn(Opcodes.ALOAD, slot);
        return CheckOp.NULL.nullJumpInsn(); // 非 null 时跳过 fail
    }

    // ======================== instanceof ========================

    private static int emitInstanceof(MethodVisitor mv, int slot, FlowNode node) {
        String className = node.<String>getRequiredAttr("value").replace('.', '/');
        mv.visitVarInsn(Opcodes.ALOAD, slot);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, className);
        return Opcodes.IFNE; // instanceof 为 true 时继续
    }

    // ======================== contains（智能分发） ========================

    @SuppressWarnings("unused")
    private static int emitContains(MethodVisitor mv, CheckOp op, int slot, IRType type,
                                    FlowNode node, CompilationContext ctx) {
        mv.visitVarInsn(Opcodes.ALOAD, slot);
        Object value = node.getRequiredAttr("value");

        if (type == IRType.STRING) {
            // String.contains(CharSequence)
            mv.visitLdcInsn((String) value);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "contains",
                    "(Ljava/lang/CharSequence;)Z", false);
        } else if (type == IRType.COLLECTION) {
            // Collection.contains(Object)
            if (value instanceof String s) mv.visitLdcInsn(s);
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Collection", "contains",
                    "(Ljava/lang/Object;)Z", true);
        } else {
            // fallback: toString().contains()
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "toString",
                    "()Ljava/lang/String;", false);
            mv.visitLdcInsn((String) value);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "contains",
                    "(Ljava/lang/CharSequence;)Z", false);
        }
        return Opcodes.IFNE; // contains 为 true 时继续
    }

    // ======================== 字符串操作（startsWith / endsWith） ========================

    @SuppressWarnings("unused")
    private static int emitStringOp(MethodVisitor mv, CheckOp op, int slot, IRType type,
                                    FlowNode node, CompilationContext ctx) {
        String value = node.getRequiredAttr("value");
        mv.visitVarInsn(Opcodes.ALOAD, slot);

        // 单字符优化：startsWith("x") → charAt(0) == 'x'
        if (value.length() == 1 && (op == CheckOp.STARTS_WITH || op == CheckOp.ENDS_WITH)) {
            if (op == CheckOp.STARTS_WITH) {
                ASMUtils.emitIntConst(mv, 0);
            } else {
                // endsWith → length()-1
                mv.visitInsn(Opcodes.DUP);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
                ASMUtils.emitIntConst(mv, 1);
                mv.visitInsn(Opcodes.ISUB);
            }
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
            ASMUtils.emitIntConst(mv, value.charAt(0));
            return Opcodes.IF_ICMPEQ; // charAt == target 时继续
        }

        // 通用路径
        mv.visitLdcInsn(value);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", op.javaStringMethodName(),
                "(Ljava/lang/String;)Z", false);
        return Opcodes.IFNE;
    }

    // ======================== matches（正则预编译） ========================

    private static int emitMatches(MethodVisitor mv, int slot, FlowNode node) {
        String hoistedField = node.getAttrOrDefault("_hoistedField", null);
        if (hoistedField != null) {
            // 外置常量池路径：invokedynamic → ConstantCallSite，JIT 折叠为常量
            mv.visitInvokeDynamicInsn(hoistedField, "()Ljava/util/regex/Pattern;",
                    BytecodeCompiler.CONST_BOOTSTRAP_HANDLE, hoistedField);
            mv.visitVarInsn(Opcodes.ALOAD, slot);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/regex/Pattern", "matcher",
                    "(Ljava/lang/CharSequence;)Ljava/util/regex/Matcher;", false);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/regex/Matcher", "matches", "()Z", false);
            return Opcodes.IFNE;
        }

        // 退化路径：String.matches()
        String pattern = node.getRequiredAttr("value");
        mv.visitVarInsn(Opcodes.ALOAD, slot);
        mv.visitLdcInsn(pattern);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "matches",
                "(Ljava/lang/String;)Z", false);
        return Opcodes.IFNE;
    }

    // ======================== in（智能分发） ========================

    @SuppressWarnings("unchecked")
    private static int emitIn(MethodVisitor mv, CheckOp op, int slot, IRType type,
                              FlowNode node, CompilationContext ctx) {
        ImmutableList<?> valueList = node.getAttrOrDefault("valueList", null);
        if (valueList == null) valueList = node.getRequiredAttr("value");

        if (valueList.size() <= CheckOp.IN_SET_THRESHOLD) {
            // ≤IN_SET_THRESHOLD 项 → 展开为多路比较（避免集合开销）
            return emitInExpanded(mv, slot, (ImmutableList<Object>) valueList, type);
        }

        // >3 项 → Set.of(...).contains(var)
        return emitInSet(mv, slot, (ImmutableList<Object>) valueList, type, node);
    }

    /**
     * 展开式 in：var==v1 || var==v2 || var==v3
     */
    private static int emitInExpanded(MethodVisitor mv, int slot,
                                      ImmutableList<Object> values, IRType type) {
        Label trueLabel = new Label();
        Label endLabel = new Label();

        for (int i = 0; i < values.size(); i++) {
            Object val = values.get(i);
            if (type == IRType.INT) {
                mv.visitVarInsn(Opcodes.ILOAD, slot);
                ASMUtils.emitIntConst(mv, ((Number) val).intValue());
                mv.visitJumpInsn(Opcodes.IF_ICMPEQ, trueLabel);
            } else if (type == IRType.ENUM) {
                mv.visitVarInsn(Opcodes.ALOAD, slot);
                mv.visitLdcInsn(val.toString());
                mv.visitVarInsn(Opcodes.ALOAD, slot);
                ASMUtils.emitEnumName(mv);
                mv.visitLdcInsn(val.toString());
                ASMUtils.emitEquals(mv);
                mv.visitJumpInsn(Opcodes.IFNE, trueLabel);
            } else {
                mv.visitVarInsn(Opcodes.ALOAD, slot);
                if (val instanceof String s) mv.visitLdcInsn(s);
                ASMUtils.emitEquals(mv);
                mv.visitJumpInsn(Opcodes.IFNE, trueLabel);
            }
        }

        // 全部不匹配
        ASMUtils.emitIntConst(mv, 0);
        mv.visitJumpInsn(Opcodes.GOTO, endLabel);

        mv.visitLabel(trueLabel);
        ASMUtils.emitIntConst(mv, 1);

        mv.visitLabel(endLabel);
        return Opcodes.IFNE; // in 结果为 true 时继续
    }

    /**
     * Set.of() 方式 in：生成不可变集合 + contains。
     */
    private static int emitInSet(MethodVisitor mv, int slot,
                                 ImmutableList<Object> values, IRType type, FlowNode node) {
        String hoistedField = node.getAttrOrDefault("_hoistedField", null);

        if (hoistedField != null) {
            // 外置常量池路径：invokedynamic → ConstantCallSite
            mv.visitInvokeDynamicInsn(hoistedField, "()Ljava/util/Set;",
                    BytecodeCompiler.CONST_BOOTSTRAP_HANDLE, hoistedField);
        } else {
            // 退化路径：动态创建 Set.of()
            int count = values.size();
            ASMUtils.emitIntConst(mv, count);
            mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
            for (int i = 0; i < count; i++) {
                mv.visitInsn(Opcodes.DUP);
                ASMUtils.emitIntConst(mv, i);
                Object val = values.get(i);
                if (val instanceof String s) {
                    mv.visitLdcInsn(s);
                } else if (val instanceof Number n) {
                    mv.visitLdcInsn(n.intValue());
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf",
                            "(I)Ljava/lang/Integer;", false);
                }
                mv.visitInsn(Opcodes.AASTORE);
            }
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Set", "of",
                    "([Ljava/lang/Object;)Ljava/util/Set;", true);
        }

        // set.contains(var)
        if (type.isPrimitive()) {
            mv.visitVarInsn(Opcodes.ILOAD, slot);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf",
                    "(I)Ljava/lang/Integer;", false);
        } else {
            mv.visitVarInsn(Opcodes.ALOAD, slot);
        }
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Set", "contains",
                "(Ljava/lang/Object;)Z", true);

        return Opcodes.IFNE;
    }

    // ======================== between ========================

    @SuppressWarnings("unused")
    private static int emitBetween(MethodVisitor mv, CheckOp op, int slot, IRType type,
                                   FlowNode node, CompilationContext ctx) {
        String hoistedField = node.getAttrOrDefault("_hoistedField", null);
        boolean useArray = hoistedField != null && type == IRType.DOUBLE;

        double low = 0, high = 0;
        if (!useArray) {
            ImmutableList<?> range = node.getAttrOrDefault("valueList", null);
            if (range == null) range = node.getRequiredAttr("value");
            low = ((Number) range.get(0)).doubleValue();
            high = ((Number) range.get(1)).doubleValue();
        }

        Label failLabel = new Label();
        Label endLabel = new Label();

        if (type == IRType.INT) {
            int iLow = (int) low, iHigh = (int) high;
            // var >= low
            mv.visitVarInsn(Opcodes.ILOAD, slot);
            ASMUtils.emitIntConst(mv, iLow);
            mv.visitJumpInsn(Opcodes.IF_ICMPLT, failLabel);
            // var <= high
            mv.visitVarInsn(Opcodes.ILOAD, slot);
            ASMUtils.emitIntConst(mv, iHigh);
            mv.visitJumpInsn(Opcodes.IF_ICMPGT, failLabel);
        } else {
            // double
            if (useArray) {
                // 从 RANGE_x 数组中获取边界值
                // var >= arr[0]
                mv.visitVarInsn(Opcodes.DLOAD, slot);
                mv.visitInvokeDynamicInsn(hoistedField, "()[D",
                        BytecodeCompiler.CONST_BOOTSTRAP_HANDLE, hoistedField);
                ASMUtils.emitIntConst(mv, 0);
                mv.visitInsn(Opcodes.DALOAD);
                mv.visitInsn(Opcodes.DCMPG);
                mv.visitJumpInsn(Opcodes.IFLT, failLabel);

                // var <= arr[1]
                mv.visitVarInsn(Opcodes.DLOAD, slot);
                mv.visitInvokeDynamicInsn(hoistedField, "()[D",
                        BytecodeCompiler.CONST_BOOTSTRAP_HANDLE, hoistedField);
                ASMUtils.emitIntConst(mv, 1);
                mv.visitInsn(Opcodes.DALOAD);
                mv.visitInsn(Opcodes.DCMPL);
                mv.visitJumpInsn(Opcodes.IFGT, failLabel);
            } else {
                // 退化路径：常量拼接
                mv.visitVarInsn(Opcodes.DLOAD, slot);
                ASMUtils.emitDoubleConst(mv, low);
                mv.visitInsn(Opcodes.DCMPG);
                mv.visitJumpInsn(Opcodes.IFLT, failLabel);

                mv.visitVarInsn(Opcodes.DLOAD, slot);
                ASMUtils.emitDoubleConst(mv, high);
                mv.visitInsn(Opcodes.DCMPL);
                mv.visitJumpInsn(Opcodes.IFGT, failLabel);
            }
        }

        // 在范围内
        ASMUtils.emitIntConst(mv, 1);
        mv.visitJumpInsn(Opcodes.GOTO, endLabel);

        mv.visitLabel(failLabel);
        ASMUtils.emitIntConst(mv, 0);

        mv.visitLabel(endLabel);
        return Opcodes.IFNE; // between 满足时继续
    }

    // ======================== 数值/相等比较（智能分发） ========================

    @SuppressWarnings("unused")
    private static int emitComparison(MethodVisitor mv, CheckOp op, int slot, IRType type,
                                      FlowNode node, CompilationContext ctx) {
        if (type == IRType.BOOLEAN) {
            return emitBooleanComparison(mv, slot, node, op);
        } else if (type == IRType.DOUBLE) {
            return emitDoubleComparison(mv, slot, node, op, ctx);
        } else if (type == IRType.INT) {
            return emitIntComparison(mv, slot, node, op);
        } else if (type == IRType.LONG) {
            return emitLongComparison(mv, slot, node, op);
        } else if (type == IRType.ENUM && op == CheckOp.EQ) {
            return emitEnumEquals(mv, slot, node);
        } else {
            return emitObjectComparison(mv, slot, node, op);
        }
    }

    /** boolean：无 value → 直接检测；有 value → 调整 */
    private static int emitBooleanComparison(MethodVisitor mv, int slot, FlowNode node, CheckOp op) {
        mv.visitVarInsn(Opcodes.ILOAD, slot);
        Object value = node.getAttrOrDefault("value", null);
        if (value == null || Boolean.TRUE.equals(value)) {
            return Opcodes.IFNE;
        } else {
            return Opcodes.IFEQ;
        }
    }

    /** double 零装箱比较 */
    private static int emitDoubleComparison(MethodVisitor mv, int slot, FlowNode node, CheckOp op,
                                            CompilationContext ctx) {
        mv.visitVarInsn(Opcodes.DLOAD, slot);
        return emitDoubleComparisonOnStack(mv, node, op, ctx);
    }

    /**
     * 栈顶已有 left double 时，发射 right 值 + DCMPG + 返回跳转 opcode。
     * <p>
     * 供 Comparison 策略内部和 {@code CheckNodeHandler.emitCondition} 的 conditionAction/MATH 路径共用。
     *
     * @param mv   MethodVisitor
     * @param node CHECK 节点（可能含 {@code valueNode} 属性）
     * @param op   比较操作符（决定 DCMPG 后的跳转方向）
     * @param ctx  编译上下文
     * @return 条件成立时的 JVM 跳转 opcode
     */
    public static int emitDoubleComparisonOnStack(MethodVisitor mv, FlowNode node, CheckOp op,
                                                  CompilationContext ctx) {
        MathNode valueNode = node.getAttrOrDefault("valueNode", null);
        if (valueNode != null) {
            MathNodeEmitter.emitWithContext(valueNode, mv, ctx);
        } else {
            ASMUtils.emitDoubleConst(mv, node.numericValue());
        }
        mv.visitInsn(Opcodes.DCMPG);
        return op.cmpJump();
    }

    /** int 零装箱比较 */
    private static int emitIntComparison(MethodVisitor mv, int slot, FlowNode node, CheckOp op) {
        mv.visitVarInsn(Opcodes.ILOAD, slot);
        ASMUtils.emitIntConst(mv, (int) node.numericValue());
        return op.intCmpJump();
    }

    /** long 比较 */
    private static int emitLongComparison(MethodVisitor mv, int slot, FlowNode node, CheckOp op) {
        mv.visitVarInsn(Opcodes.LLOAD, slot);
        ASMUtils.emitLongConst(mv, (long) node.numericValue());
        mv.visitInsn(Opcodes.LCMP);
        return op.cmpJump();
    }

    /** 枚举引用比较（Enum.name().equals()，安全可靠） */
    private static int emitEnumEquals(MethodVisitor mv, int slot, FlowNode node) {
        mv.visitVarInsn(Opcodes.ALOAD, slot);
        String enumValue = node.getRequiredAttr("value").toString();
        mv.visitLdcInsn(enumValue);
        mv.visitVarInsn(Opcodes.ALOAD, slot);
        ASMUtils.emitEnumName(mv);
        mv.visitInsn(Opcodes.SWAP);
        ASMUtils.emitEquals(mv);
        return Opcodes.IFNE;
    }

    /** 对象 equals 比较 */
    private static int emitObjectComparison(MethodVisitor mv, int slot, FlowNode node, CheckOp op) {
        mv.visitVarInsn(Opcodes.ALOAD, slot);
        Object value = node.getAttrOrDefault("value", null);
        if (value instanceof String s) {
            mv.visitLdcInsn(s);
        }
        ASMUtils.emitEquals(mv);
        return op.afterEqualsJump();
    }
}
