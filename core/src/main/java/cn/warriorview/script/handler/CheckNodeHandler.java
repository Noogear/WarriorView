package cn.warriorview.script.handler;

import cn.warriorview.script.codegen.BytecodeCompiler;
import cn.warriorview.script.core.CompilationContext;
import cn.warriorview.script.core.ScriptIR;
import cn.warriorview.script.core.ScriptIR.FlowNode;
import cn.warriorview.script.core.ScriptIR.FlowNodeType;
import cn.warriorview.script.core.ScriptIR.IRType;
import cn.warriorview.script.core.ScriptIR.NodeCapability;
import cn.warriorview.script.parser.ScriptParser;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/**
 * CHECK 节点处理器（增强版）。
 * <p>
 * 支持 10 个基础操作符，所有操作符前均可加 {@code !} 前缀取反。
 * 编译时根据 {@link IRType} 智能选择零装箱字节码指令。
 * <p>
 * 操作符：{@code null, ==, >, <, >=, <=, contains, starts_with, ends_with, matches, instanceof, in, between}
 */
@SuppressWarnings("null")
public final class CheckNodeHandler implements ScriptIR.FlowNodeHandler {

    static {
        FlowNodeType.registerHandler(FlowNodeType.CHECK, CheckNodeHandler::new);
    }

    public static void init() {
    }

    @Override
    public FlowNode parse(Map<String, Object> yaml) {
        String variable = (String) yaml.get("variable");
        String op = (String) yaml.get("op");
        Object value = yaml.get("value");

        ImmutableMap.Builder<String, Object> attrs = ImmutableMap.builder();
        attrs.put("variable", variable);
        attrs.put("op", op);

        double numericValue = 0.0;

        if (value != null) {
            if (value instanceof String s) {
                value = ScriptParser.ValueParser.parseNumber(s);
            }
            attrs.put("value", value);
            attrs.put("valueType", ScriptParser.ValueParser.inferType(value));

            // 数值存入 numericValue 字段（零装箱路径）
            if (value instanceof Number n) {
                numericValue = n.doubleValue();
            }
        }

        // in 操作符的值列表
        if (value instanceof List<?> list) {
            attrs.put("valueList", ImmutableList.copyOf(list));
        }

        return new FlowNode(FlowNodeType.CHECK, attrs.build(), numericValue, 0);
    }

    @Override
    public void emit(FlowNode node, MethodVisitor mv, CompilationContext ctx) {
        String rawOp = node.attr("op");

        // ! 前缀拆分
        boolean negate = rawOp.startsWith("!");
        String op = negate ? rawOp.substring(1) : rawOp;

        String variable = node.attr("variable");
        int slot = ctx.getSlot(variable);
        IRType type = ctx.getType(variable);

        Label continueLabel = new Label();

        // 发射条件检查 → 得到"条件满足时跳转"的 opcode
        int jumpOp = switch (op) {
            case "null" -> emitNullCheck(mv, slot);
            case "instanceof" -> emitInstanceof(mv, slot, node);
            case "contains" -> emitContains(mv, slot, node, type);
            case "starts_with" -> emitStringOp(mv, "startsWith", slot, node);
            case "ends_with" -> emitStringOp(mv, "endsWith", slot, node);
            case "matches" -> emitMatches(mv, slot, node);
            case "in" -> emitIn(mv, slot, node, type);
            case "between" -> emitBetween(mv, slot, node, type);
            default -> emitComparison(mv, slot, type, op, node);
        };

        // negate: 翻转跳转方向（零额外指令）
        if (negate)
            jumpOp = invertJump(jumpOp);

        mv.visitJumpInsn(jumpOp, continueLabel);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitLabel(continueLabel);
    }

    // ======================== null ========================

    private int emitNullCheck(MethodVisitor mv, int slot) {
        mv.visitVarInsn(Opcodes.ALOAD, slot);
        return Opcodes.IFNONNULL; // 非 null 时继续
    }

    // ======================== instanceof ========================

    private int emitInstanceof(MethodVisitor mv, int slot, FlowNode node) {
        String className = ((String) node.attr("value")).replace('.', '/');
        mv.visitVarInsn(Opcodes.ALOAD, slot);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, className);
        return Opcodes.IFNE; // instanceof 为 true 时继续
    }

    // ======================== contains（智能分发） ========================

    private int emitContains(MethodVisitor mv, int slot, FlowNode node, IRType type) {
        mv.visitVarInsn(Opcodes.ALOAD, slot);
        Object value = node.attr("value");

        if (type == IRType.STRING) {
            // String.contains(CharSequence)
            mv.visitLdcInsn((String) value);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "contains",
                    "(Ljava/lang/CharSequence;)Z", false);
        } else if (type == IRType.COLLECTION) {
            // Collection.contains(Object)
            if (value instanceof String s)
                mv.visitLdcInsn(s);
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

    // ======================== 字符串操作 ========================

    private int emitStringOp(MethodVisitor mv, String methodName, int slot, FlowNode node) {
        String value = node.attr("value");
        mv.visitVarInsn(Opcodes.ALOAD, slot);

        // 单字符优化：startsWith("x") → charAt(0) == 'x'
        if (value.length() == 1 && ("startsWith".equals(methodName) || "endsWith".equals(methodName))) {
            if ("startsWith".equals(methodName)) {
                BytecodeCompiler.emitIntConst(mv, 0);
            } else {
                // endsWith → length()-1
                mv.visitInsn(Opcodes.DUP);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
                BytecodeCompiler.emitIntConst(mv, 1);
                mv.visitInsn(Opcodes.ISUB);
            }
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
            BytecodeCompiler.emitIntConst(mv, value.charAt(0));
            return Opcodes.IF_ICMPEQ; // charAt == target 时继续
        }

        // 通用路径
        mv.visitLdcInsn(value);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", methodName,
                "(Ljava/lang/String;)Z", false);
        return Opcodes.IFNE;
    }

    // ======================== matches（正则预编译） ========================

    private int emitMatches(MethodVisitor mv, int slot, FlowNode node) {
        // TODO: 将正则预编译为 static final Pattern 常量字段
        // 当前方案：先用 String.matches()，后续可优化
        String pattern = node.attr("value");
        mv.visitVarInsn(Opcodes.ALOAD, slot);
        mv.visitLdcInsn(pattern);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "matches",
                "(Ljava/lang/String;)Z", false);
        return Opcodes.IFNE;
    }

    // ======================== in（智能分发） ========================

    @SuppressWarnings("unchecked")
    private int emitIn(MethodVisitor mv, int slot, FlowNode node, IRType type) {
        ImmutableList<?> valueList = node.attr("valueList");
        if (valueList == null)
            valueList = node.attr("value");

        if (valueList.size() <= 3) {
            // ≤3 项 → 展开为多路比较（避免集合开销）
            return emitInExpanded(mv, slot, (ImmutableList<Object>) valueList, type);
        }

        // >3 项 → Set.of(...).contains(var)
        return emitInSet(mv, slot, (ImmutableList<Object>) valueList, type);
    }

    /**
     * 展开式 in：var==v1 || var==v2 || var==v3
     */
    private int emitInExpanded(MethodVisitor mv, int slot,
            ImmutableList<Object> values, IRType type) {
        Label trueLabel = new Label();
        Label endLabel = new Label();

        for (int i = 0; i < values.size(); i++) {
            Object val = values.get(i);
            if (type == IRType.INT) {
                mv.visitVarInsn(Opcodes.ILOAD, slot);
                BytecodeCompiler.emitIntConst(mv, ((Number) val).intValue());
                mv.visitJumpInsn(Opcodes.IF_ICMPEQ, trueLabel);
            } else if (type == IRType.ENUM) {
                mv.visitVarInsn(Opcodes.ALOAD, slot);
                mv.visitLdcInsn(val.toString());
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Enum", "name",
                        "()Ljava/lang/String;", false);
                // 需要先swap: enum.name() 在栈顶，但我们需要 name.equals(val)
                // 改为：加载 val → 加载 enum.name → equals
                // 重新组织:
                mv.visitVarInsn(Opcodes.ALOAD, slot);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Enum", "name",
                        "()Ljava/lang/String;", false);
                mv.visitLdcInsn(val.toString());
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals",
                        "(Ljava/lang/Object;)Z", false);
                mv.visitJumpInsn(Opcodes.IFNE, trueLabel);
            } else {
                mv.visitVarInsn(Opcodes.ALOAD, slot);
                if (val instanceof String s)
                    mv.visitLdcInsn(s);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "equals",
                        "(Ljava/lang/Object;)Z", false);
                mv.visitJumpInsn(Opcodes.IFNE, trueLabel);
            }
        }

        // 全部不匹配
        BytecodeCompiler.emitIntConst(mv, 0);
        mv.visitJumpInsn(Opcodes.GOTO, endLabel);

        mv.visitLabel(trueLabel);
        BytecodeCompiler.emitIntConst(mv, 1);

        mv.visitLabel(endLabel);
        return Opcodes.IFNE; // in 结果为 true 时继续
    }

    /**
     * Set.of() 方式 in：生成不可变集合 + contains。
     */
    private int emitInSet(MethodVisitor mv, int slot,
            ImmutableList<Object> values, IRType type) {
        // 创建 Set.of(values...)
        // 对于少于 10 个参数，使用 Set.of() 具名重载
        int count = values.size();

        for (Object val : values) {
            if (val instanceof String s) {
                mv.visitLdcInsn(s);
            } else if (val instanceof Number n) {
                // 需要装箱为 Object
                mv.visitLdcInsn(n.intValue());
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf",
                        "(I)Ljava/lang/Integer;", false);
            }
        }

        // Set.of(Object...) — 使用 varargs 版本
        mv.visitIntInsn(Opcodes.BIPUSH, count);
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");

        // 回填数组——简化为直接 Set.of(a,b,c,...) 调用
        // 由于 Set.of 有最多 10 个参数的重载，使用 varargs
        // 重新实现：先创建数组
        // 重构为简洁方案
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Set", "of",
                "([Ljava/lang/Object;)Ljava/util/Set;", true);

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

    private int emitBetween(MethodVisitor mv, int slot, FlowNode node, IRType type) {
        // value 应为 [low, high] 列表
        ImmutableList<?> range = node.attr("valueList");
        if (range == null)
            range = node.attr("value");

        double low = ((Number) range.get(0)).doubleValue();
        double high = ((Number) range.get(1)).doubleValue();

        Label failLabel = new Label();
        Label endLabel = new Label();

        if (type == IRType.INT) {
            int iLow = (int) low, iHigh = (int) high;
            // var >= low
            mv.visitVarInsn(Opcodes.ILOAD, slot);
            BytecodeCompiler.emitIntConst(mv, iLow);
            mv.visitJumpInsn(Opcodes.IF_ICMPLT, failLabel);
            // var <= high
            mv.visitVarInsn(Opcodes.ILOAD, slot);
            BytecodeCompiler.emitIntConst(mv, iHigh);
            mv.visitJumpInsn(Opcodes.IF_ICMPGT, failLabel);
        } else {
            // double
            // var >= low
            mv.visitVarInsn(Opcodes.DLOAD, slot);
            BytecodeCompiler.emitDoubleConst(mv, low);
            mv.visitInsn(Opcodes.DCMPG);
            mv.visitJumpInsn(Opcodes.IFLT, failLabel);
            // var <= high
            mv.visitVarInsn(Opcodes.DLOAD, slot);
            BytecodeCompiler.emitDoubleConst(mv, high);
            mv.visitInsn(Opcodes.DCMPL);
            mv.visitJumpInsn(Opcodes.IFGT, failLabel);
        }

        // 在范围内
        BytecodeCompiler.emitIntConst(mv, 1);
        mv.visitJumpInsn(Opcodes.GOTO, endLabel);

        mv.visitLabel(failLabel);
        BytecodeCompiler.emitIntConst(mv, 0);

        mv.visitLabel(endLabel);
        return Opcodes.IFNE; // between 满足时继续
    }

    // ======================== 数值/相等比较（智能分发） ========================

    private int emitComparison(MethodVisitor mv, int slot, IRType type, String op, FlowNode node) {
        if (type == IRType.BOOLEAN) {
            return emitBooleanComparison(mv, slot, node, op);
        } else if (type == IRType.DOUBLE) {
            return emitDoubleComparison(mv, slot, node, op);
        } else if (type == IRType.INT) {
            return emitIntComparison(mv, slot, node, op);
        } else if (type == IRType.LONG) {
            return emitLongComparison(mv, slot, node, op);
        } else if (type == IRType.ENUM && "==".equals(op)) {
            return emitEnumEquals(mv, slot, node);
        } else {
            return emitObjectComparison(mv, slot, node, op);
        }
    }

    /** boolean：无 value → 直接检测；有 value → 调整 */
    private int emitBooleanComparison(MethodVisitor mv, int slot, FlowNode node, String op) {
        mv.visitVarInsn(Opcodes.ILOAD, slot);
        Object value = node.attr("value");
        if (value == null || Boolean.TRUE.equals(value)) {
            // is_true: IFNE 继续
            return Opcodes.IFNE;
        } else {
            // is_false: IFEQ 继续
            return Opcodes.IFEQ;
        }
    }

    /** double 零装箱比较 */
    private int emitDoubleComparison(MethodVisitor mv, int slot, FlowNode node, String op) {
        mv.visitVarInsn(Opcodes.DLOAD, slot);
        BytecodeCompiler.emitDoubleConst(mv, node.numericValue());
        mv.visitInsn(Opcodes.DCMPG);
        return switch (op) {
            case ">" -> Opcodes.IFGT;
            case ">=" -> Opcodes.IFGE;
            case "<" -> Opcodes.IFLT;
            case "<=" -> Opcodes.IFLE;
            case "==" -> Opcodes.IFEQ;
            default -> throw new IllegalArgumentException("Unsupported op for double: " + op);
        };
    }

    /** int 零装箱比较 */
    private int emitIntComparison(MethodVisitor mv, int slot, FlowNode node, String op) {
        mv.visitVarInsn(Opcodes.ILOAD, slot);
        BytecodeCompiler.emitIntConst(mv, (int) node.numericValue());
        return switch (op) {
            case ">" -> Opcodes.IF_ICMPGT;
            case ">=" -> Opcodes.IF_ICMPGE;
            case "<" -> Opcodes.IF_ICMPLT;
            case "<=" -> Opcodes.IF_ICMPLE;
            case "==" -> Opcodes.IF_ICMPEQ;
            default -> throw new IllegalArgumentException("Unsupported op for int: " + op);
        };
    }

    /** long 比较 */
    private int emitLongComparison(MethodVisitor mv, int slot, FlowNode node, String op) {
        mv.visitVarInsn(Opcodes.LLOAD, slot);
        BytecodeCompiler.emitLongConst(mv, (long) node.numericValue());
        mv.visitInsn(Opcodes.LCMP);
        return switch (op) {
            case ">" -> Opcodes.IFGT;
            case ">=" -> Opcodes.IFGE;
            case "<" -> Opcodes.IFLT;
            case "<=" -> Opcodes.IFLE;
            case "==" -> Opcodes.IFEQ;
            default -> throw new IllegalArgumentException("Unsupported op for long: " + op);
        };
    }

    /** 枚举引用比较（IF_ACMPEQ，单例安全） */
    private int emitEnumEquals(MethodVisitor mv, int slot, FlowNode node) {
        mv.visitVarInsn(Opcodes.ALOAD, slot);
        // 加载枚举常量：Enum.valueOf(class, name)
        String enumValue = node.attr("value").toString();
        mv.visitLdcInsn(enumValue);
        // 通过 name().equals() 比较（更通用）
        mv.visitVarInsn(Opcodes.ALOAD, slot);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Enum", "name",
                "()Ljava/lang/String;", false);
        mv.visitInsn(Opcodes.SWAP);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals",
                "(Ljava/lang/Object;)Z", false);
        return Opcodes.IFNE;
    }

    /** 对象 equals 比较 */
    private int emitObjectComparison(MethodVisitor mv, int slot, FlowNode node, String op) {
        mv.visitVarInsn(Opcodes.ALOAD, slot);
        Object value = node.attr("value");
        if (value instanceof String s) {
            mv.visitLdcInsn(s);
        }
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "equals",
                "(Ljava/lang/Object;)Z", false);
        return "==".equals(op) ? Opcodes.IFNE : Opcodes.IFEQ;
    }

    // ======================== 跳转取反映射 ========================

    /**
     * 翻转 JVM 跳转 opcode（纯映射表，零额外指令）。
     */
    private static int invertJump(int opcode) {
        return switch (opcode) {
            case Opcodes.IFEQ -> Opcodes.IFNE;
            case Opcodes.IFNE -> Opcodes.IFEQ;
            case Opcodes.IFLT -> Opcodes.IFGE;
            case Opcodes.IFGE -> Opcodes.IFLT;
            case Opcodes.IFGT -> Opcodes.IFLE;
            case Opcodes.IFLE -> Opcodes.IFGT;
            case Opcodes.IF_ICMPEQ -> Opcodes.IF_ICMPNE;
            case Opcodes.IF_ICMPNE -> Opcodes.IF_ICMPEQ;
            case Opcodes.IF_ICMPLT -> Opcodes.IF_ICMPGE;
            case Opcodes.IF_ICMPGE -> Opcodes.IF_ICMPLT;
            case Opcodes.IF_ICMPGT -> Opcodes.IF_ICMPLE;
            case Opcodes.IF_ICMPLE -> Opcodes.IF_ICMPGT;
            case Opcodes.IF_ACMPEQ -> Opcodes.IF_ACMPNE;
            case Opcodes.IF_ACMPNE -> Opcodes.IF_ACMPEQ;
            case Opcodes.IFNULL -> Opcodes.IFNONNULL;
            case Opcodes.IFNONNULL -> Opcodes.IFNULL;
            default -> throw new IllegalArgumentException("Cannot invert opcode: " + opcode);
        };
    }

    @Override
    public EnumSet<NodeCapability> capabilities() {
        return EnumSet.of(NodeCapability.HAS_CONDITION, NodeCapability.FOLDABLE);
    }
}
