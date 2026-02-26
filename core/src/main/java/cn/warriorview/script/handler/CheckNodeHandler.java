package cn.warriorview.script.handler;

import cn.warriorview.script.codegen.ASMUtils;

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

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import cn.warriorview.script.optimizer.ScriptOptimizer;

/**
 * CHECK 节点处理器（增强版）。
 * <p>
 * 支持 10 个基础操作符，所有操作符前均可加 {@code !} 前缀取反。
 * 编译时根据 {@link IRType} 智能选择零装箱字节码指令。
 * <p>
 * 操作符：{@code null, ==, >, <, >=, <=, contains, starts_with, ends_with, matches, instanceof, in, between}
 */
@SuppressWarnings("null")
public final class CheckNodeHandler
        implements ScriptIR.FlowNodeHandler, ScriptIR.ConditionEmitter, ScriptIR.NodeTraverser,
        ScriptIR.ConstantHoister, ScriptIR.ConstantFolder, ScriptIR.RangePropagator, ScriptIR.VariableConsumer {

    static {
        FlowNodeType.registerHandler(FlowNodeType.CHECK, CheckNodeHandler::new);
    }

    public static void init() {
    }

    @Override
    @SuppressWarnings("unchecked")
    public FlowNode parse(Map<String, Object> yaml) {
        Object variableObj = yaml.get("variable");
        String variable = null;
        FlowNode conditionAction = null;

        if (variableObj instanceof Map) {
            conditionAction = ScriptParser.parseFlowNode((Map<String, Object>) variableObj);
        } else if (variableObj != null) {
            variable = variableObj.toString();
        }

        String op = (String) yaml.get("op");
        Object value = yaml.get("value");

        ImmutableMap.Builder<String, Object> attrs = ImmutableMap.builder();
        if (variable != null) {
            attrs.put("variable", variable);
        }
        if (conditionAction != null) {
            attrs.put("conditionAction", conditionAction);
        }
        if (op != null) {
            attrs.put("op", op);
        }

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

        // 解析 on_fail 列表
        List<?> onFailRaw = (List<?>) yaml.get("on_fail");
        if (onFailRaw != null) {
            attrs.put("onFailNodes", ScriptParser.parseFlow(onFailRaw));
        }

        return new FlowNode(FlowNodeType.CHECK, attrs.build(), numericValue, 0);
    }

    @Override
    public void emit(FlowNode node, MethodVisitor mv, CompilationContext ctx) {
        int jumpOp = emitCondition(node, mv, ctx);

        Label continueLabel = new Label();
        mv.visitJumpInsn(jumpOp, continueLabel);

        // 分支失败处理：执行所有的 on_fail 动作
        emitOnFail(node, mv, ctx);

        mv.visitInsn(Opcodes.RETURN);
        mv.visitLabel(continueLabel);
    }

    // ======================== 可复用条件原语 ========================

    /**
     * 发射单个条件的比较字节码，返回"条件成立时应跳转"的 opcode。
     * <p>
     * 实装 {@link ScriptIR.ConditionEmitter} 接口供复合节点复用。
     * 已包含 negate（{@code !} 前缀）处理。
     */
    @Override
    public int emitCondition(FlowNode node, MethodVisitor mv, CompilationContext ctx) {
        FlowNode conditionAction = node.getAttrOrDefault("conditionAction", null);
        String op = null;
        boolean negate = false;

        if (conditionAction == null) {
            String rawOp = node.getRequiredAttr("op");
            negate = rawOp.startsWith("!");
            op = negate ? rawOp.substring(1) : rawOp;
        }

        int jumpOp;

        if (conditionAction != null) {
            String sinkingProp = conditionAction.getAttrOrDefault("_sinking_property", null);
            if (sinkingProp != null) {
                int tempSlot = ctx.nextSlot();
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                java.util.List<cn.warriorview.script.parser.accessor.PropertyAccessor> accessors = cn.warriorview.script.parser.ScriptParser.PropertyResolver
                        .resolveAccessors(
                                com.google.common.reflect.TypeToken.of(ctx.payloadClass()), sinkingProp);
                for (cn.warriorview.script.parser.accessor.PropertyAccessor acr : accessors) {
                    acr.emitLoad(mv);
                }

                IRType exactType = conditionAction.getRequiredAttr("returnType");
                int storeOp = cn.warriorview.script.codegen.ASMUtils.storeOpcode(exactType);
                mv.visitVarInsn(storeOp, tempSlot);

                jumpOp = emitSinkingCheck(mv, op, tempSlot, exactType, node);
            } else {
                conditionAction.type().handler().emit(conditionAction, mv, ctx);
                jumpOp = Opcodes.IFNE;
            }
        } else {
            String variable = node.getRequiredAttr("variable");
            int slot = ctx.getSlot(variable);
            IRType type = ctx.getType(variable);

            validateOpType(op, variable, type);

            jumpOp = emitSinkingCheck(mv, op, slot, type, node);
        }

        if (negate)
            jumpOp = cn.warriorview.script.codegen.ASMUtils.invertJump(jumpOp);

        return jumpOp;
    }

    /**
     * 发射 on_fail 动作列表。供 {@link CompositeCheckHandler} 复用。
     */
    void emitOnFail(FlowNode node, MethodVisitor mv, CompilationContext ctx) {
        ImmutableList<FlowNode> onFailNodes = node.getAttrOrDefault("onFailNodes", null);
        if (onFailNodes != null) {
            for (FlowNode failNode : onFailNodes) {
                failNode.type().handler().emit(failNode, mv, ctx);
            }
        }
    }

    /**
     * AOT 语义验证：操作符与变量类型的兼容性检查。
     */
    private void validateOpType(String op, String variable, IRType type) {
        if (">".equals(op) || ">=".equals(op) || "<".equals(op) || "<=".equals(op) || "between".equals(op)) {
            if (type != IRType.INT && type != IRType.LONG && type != IRType.DOUBLE) {
                throw new cn.warriorview.script.core.ScriptCompileException(
                        String.format(
                                "Operator '%s' requires a numeric type (INT/LONG/DOUBLE), but variable '%s' is of type %s.",
                                op, variable, type));
            }
        } else if ("starts_with".equals(op) || "ends_with".equals(op) || "matches".equals(op)) {
            if (type != IRType.STRING) {
                throw new cn.warriorview.script.core.ScriptCompileException(
                        String.format("Operator '%s' requires a STRING type, but variable '%s' is of type %s.",
                                op, variable, type));
            }
        } else if ("contains".equals(op)) {
            if (type != IRType.STRING && type != IRType.COLLECTION) {
                throw new cn.warriorview.script.core.ScriptCompileException(
                        String.format(
                                "Operator '%s' requires a STRING or COLLECTION type, but variable '%s' is of type %s.",
                                op, variable, type));
            }
        }
    }

    // ======================== null ========================

    private int emitSinkingCheck(MethodVisitor mv, String op, int slot, IRType type, FlowNode node) {
        return switch (op) {
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
    }

    private int emitNullCheck(MethodVisitor mv, int slot) {
        mv.visitVarInsn(Opcodes.ALOAD, slot);
        return Opcodes.IFNONNULL; // 非 null 时继续
    }

    // ======================== instanceof ========================

    private int emitInstanceof(MethodVisitor mv, int slot, FlowNode node) {
        String className = node.<String>getRequiredAttr("value").replace('.', '/');
        mv.visitVarInsn(Opcodes.ALOAD, slot);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, className);
        return Opcodes.IFNE; // instanceof 为 true 时继续
    }

    // ======================== contains（智能分发） ========================

    private int emitContains(MethodVisitor mv, int slot, FlowNode node, IRType type) {
        mv.visitVarInsn(Opcodes.ALOAD, slot);
        Object value = node.getRequiredAttr("value");

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
        String value = node.getRequiredAttr("value");
        mv.visitVarInsn(Opcodes.ALOAD, slot);

        // 单字符优化：startsWith("x") → charAt(0) == 'x'
        if (value.length() == 1 && ("startsWith".equals(methodName) || "endsWith".equals(methodName))) {
            if ("startsWith".equals(methodName)) {
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
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", methodName,
                "(Ljava/lang/String;)Z", false);
        return Opcodes.IFNE;
    }

    // ======================== matches（正则预编译） ========================

    private int emitMatches(MethodVisitor mv, int slot, FlowNode node) {
        String hoistedField = node.getAttrOrDefault("_hoistedField", null);
        if (hoistedField != null) {
            // 预编译 Pattern 优化路径
            // pattern.matcher(var).matches()
            mv.visitFieldInsn(Opcodes.GETSTATIC, node.getRequiredAttr("_className"), hoistedField,
                    "Ljava/util/regex/Pattern;");
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
    private int emitIn(MethodVisitor mv, int slot, FlowNode node, IRType type) {
        ImmutableList<?> valueList = node.getAttrOrDefault("valueList", null);
        if (valueList == null)
            valueList = node.getRequiredAttr("value");

        if (valueList.size() <= 3) {
            // ≤3 项 → 展开为多路比较（避免集合开销）
            return emitInExpanded(mv, slot, (ImmutableList<Object>) valueList, type);
        }

        // >3 项 → Set.of(...).contains(var)
        return emitInSet(mv, slot, (ImmutableList<Object>) valueList, type, node);
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
                if (val instanceof String s)
                    mv.visitLdcInsn(s);
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
    private int emitInSet(MethodVisitor mv, int slot,
            ImmutableList<Object> values, IRType type, FlowNode node) {
        String hoistedField = node.getAttrOrDefault("_hoistedField", null);

        if (hoistedField != null) {
            // 取 clinit 初始化好的 Set 常量
            mv.visitFieldInsn(Opcodes.GETSTATIC, node.getRequiredAttr("_className"), hoistedField, "Ljava/util/Set;");
        } else {
            // 退化路径：动态创建 Set.of()
            int count = values.size();
            for (Object val : values) {
                if (val instanceof String s) {
                    mv.visitLdcInsn(s);
                } else if (val instanceof Number n) {
                    mv.visitLdcInsn(n.intValue());
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf",
                            "(I)Ljava/lang/Integer;", false);
                }
            }
            mv.visitIntInsn(Opcodes.BIPUSH, count);
            mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
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

    private int emitBetween(MethodVisitor mv, int slot, FlowNode node, IRType type) {
        String hoistedField = node.getAttrOrDefault("_hoistedField", null);
        boolean useArray = hoistedField != null && type == IRType.DOUBLE;

        double low = 0, high = 0;
        if (!useArray) {
            ImmutableList<?> range = node.getAttrOrDefault("valueList", null);
            if (range == null)
                range = node.getRequiredAttr("value");
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
                mv.visitFieldInsn(Opcodes.GETSTATIC, node.getRequiredAttr("_className"), hoistedField, "[D");
                ASMUtils.emitIntConst(mv, 0);
                mv.visitInsn(Opcodes.DALOAD);
                mv.visitInsn(Opcodes.DCMPG);
                mv.visitJumpInsn(Opcodes.IFLT, failLabel);

                // var <= arr[1]
                mv.visitVarInsn(Opcodes.DLOAD, slot);
                mv.visitFieldInsn(Opcodes.GETSTATIC, node.getRequiredAttr("_className"), hoistedField, "[D");
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
        Object value = node.getAttrOrDefault("value", null);
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
        ASMUtils.emitDoubleConst(mv, node.numericValue());
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
        ASMUtils.emitIntConst(mv, (int) node.numericValue());
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
        ASMUtils.emitLongConst(mv, (long) node.numericValue());
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
        String enumValue = node.getRequiredAttr("value").toString();
        mv.visitLdcInsn(enumValue);
        mv.visitVarInsn(Opcodes.ALOAD, slot);
        ASMUtils.emitEnumName(mv);
        mv.visitInsn(Opcodes.SWAP);
        ASMUtils.emitEquals(mv);
        return Opcodes.IFNE;
    }

    /** 对象 equals 比较 */
    private int emitObjectComparison(MethodVisitor mv, int slot, FlowNode node, String op) {
        mv.visitVarInsn(Opcodes.ALOAD, slot);
        Object value = node.getAttrOrDefault("value", null);
        if (value instanceof String s) {
            mv.visitLdcInsn(s);
        }
        ASMUtils.emitEquals(mv);
        return "==".equals(op) ? Opcodes.IFNE : Opcodes.IFEQ;
    }

    @Override
    public EnumSet<NodeCapability> capabilities() {
        return EnumSet.of(NodeCapability.HAS_CONDITION, NodeCapability.FOLDABLE);
    }

    @Override
    public Iterable<FlowNode> traverseChildren(FlowNode node) {
        ArrayList<FlowNode> children = new ArrayList<>();
        FlowNode conditionAction = node.getAttrOrDefault("conditionAction", null);
        if (conditionAction != null) {
            children.add(conditionAction);
        }
        ImmutableList<FlowNode> onFailNodes = node.getAttrOrDefault("onFailNodes", null);
        if (onFailNodes != null) {
            children.addAll(onFailNodes);
        }
        return children;
    }

    @Override
    public FlowNode hoistConstants(FlowNode node, List<CompilationContext.ConstantDef> defs, int[] counter) {
        String rawOp = node.getAttrOrDefault("op", null);
        if (rawOp == null)
            return node;
        String op = rawOp.startsWith("!") ? rawOp.substring(1) : rawOp;
        String fieldName = null;

        if ("matches".equals(op)) {
            String pattern = node.getAttrOrDefault("value", null);
            if (pattern != null) {
                fieldName = "PATTERN_" + counter[0]++;
                defs.add(new CompilationContext.ConstantDef(fieldName, CompilationContext.ConstantKind.PATTERN,
                        pattern));
            }
        } else if ("in".equals(op)) {
            ImmutableList<?> list = node.getAttrOrDefault("valueList", null);
            if (list == null)
                list = node.getAttrOrDefault("value", null);
            if (list instanceof ImmutableList<?> vals && vals.size() > 3) {
                fieldName = "SET_" + counter[0]++;
                defs.add(new CompilationContext.ConstantDef(fieldName, CompilationContext.ConstantKind.STRING_SET,
                        vals));
            }
        } else if ("between".equals(op)) {
            ImmutableList<?> range = node.getAttrOrDefault("valueList", null);
            if (range == null)
                range = node.getAttrOrDefault("value", null);
            if (range instanceof ImmutableList<?> vals && vals.size() == 2) {
                double[] arr = {
                        ((Number) vals.get(0)).doubleValue(),
                        ((Number) vals.get(1)).doubleValue()
                };
                fieldName = "RANGE_" + counter[0]++;
                defs.add(new CompilationContext.ConstantDef(fieldName, CompilationContext.ConstantKind.DOUBLE_ARRAY,
                        arr));
            }
        }
        return fieldName != null ? node.withAttr("_hoistedField", fieldName) : node;
    }

    // ======================== 实现脱离优化接口 ========================

    @Override
    public Boolean evaluateFold(ScriptIR.FlowNode node, cn.warriorview.script.core.CompilationContext ctx) {
        String varName = node.getAttrOrDefault("variable", null);
        if (varName == null || !ctx.isConstant(varName))
            return null;

        OpInfo info = parseOp(node);

        Object varValue = ctx.getConstant(varName);
        Boolean result = evaluateBaseOp(info.op(), varValue, node);
        if (result != null && info.negate())
            result = !result;
        return result;
    }

    private Boolean evaluateBaseOp(String op, Object varValue, ScriptIR.FlowNode node) {
        if ("null".equals(op))
            return varValue == null;
        if (varValue == null)
            return null;

        Object cmpValue = node.getAttrOrDefault("value", null);
        if (cmpValue == null && "==".equals(op) && varValue instanceof Boolean b) {
            return b;
        }
        if (cmpValue == null)
            return null;

        if (varValue instanceof Number v && cmpValue instanceof Number c) {
            double vd = v.doubleValue(), cd = c.doubleValue();
            return switch (op) {
                case ">" -> vd > cd;
                case ">=" -> vd >= cd;
                case "<" -> vd < cd;
                case "<=" -> vd <= cd;
                case "==" -> vd == cd;
                default -> null;
            };
        }

        if ("==".equals(op))
            return varValue.equals(cmpValue);
        if ("contains".equals(op) && varValue instanceof String s && cmpValue instanceof String sub)
            return s.contains(sub);
        return null;
    }

    @Override
    public Boolean tryFoldWithRange(ScriptIR.FlowNode node, ScriptOptimizer.ValueRange range) {
        OpInfo info = parseOp(node);

        Boolean foldResult = tryFoldWithRangeOp(range, info.op(), node);
        if (foldResult != null && info.negate()) {
            return !foldResult;
        }
        return foldResult;
    }

    private Boolean tryFoldWithRangeOp(ScriptOptimizer.ValueRange range, String op,
            ScriptIR.FlowNode node) {
        if (">".equals(op) || ">=".equals(op) || "<".equals(op) || "<=".equals(op) || "==".equals(op)) {
            Object value = node.getAttrOrDefault("value", null);
            if (value instanceof Number n) {
                return range.canFold(op, n.doubleValue());
            }
            return range.canFoldExact(op, value);
        }
        if (range.nonNull()) {
            if ("null".equals(op))
                return Boolean.FALSE;
            if ("!null".equals(op))
                return Boolean.TRUE;
        }
        return null;
    }

    @Override
    public ScriptOptimizer.ValueRange updateRange(ScriptIR.FlowNode node, ScriptOptimizer.ValueRange range) {
        OpInfo info = parseOp(node);

        Object value = node.getAttrOrDefault("value", null);
        double d = value instanceof Number n ? n.doubleValue() : 0;

        return switch (info.op()) {
            case ">" -> range.withMin(d + Double.MIN_VALUE);
            case ">=" -> range.withMin(d);
            case "<" -> range.withMax(d - Double.MIN_VALUE);
            case "<=" -> range.withMax(d);
            case "==" -> value != null ? range.withExact(value) : range;
            case "null" -> info.negate() ? range.withNonNull() : range;
            default -> range;
        };
    }

    private record OpInfo(String op, boolean negate) {
    }

    private OpInfo parseOp(ScriptIR.FlowNode node) {
        String rawOp = node.getAttrOrDefault("op", null);
        boolean negate = rawOp.startsWith("!");
        return new OpInfo(negate ? rawOp.substring(1) : rawOp, negate);
    }
}
