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

        // 缺口3：双重取反规范化——偶数个 ! 前缀抵消，奇数个保留 1 个
        if (op != null) {
            int bangCount = 0;
            while (bangCount < op.length() && op.charAt(bangCount) == '!') bangCount++;
            if (bangCount >= 2) {
                op = (bangCount % 2 == 1 ? "!" : "") + op.substring(bangCount);
            }
        }

        // AOT: 无 conditionAction 时 op 必填
        if (conditionAction == null && op == null) {
            throw new cn.warriorview.script.core.ScriptCompileException(
                    "CHECK node requires an 'op' field when not using inline conditionAction.");
        }

        // AOT: instanceof 类名编译期验证
        if ("instanceof".equals(op) && value instanceof String className) {
            try {
                Class.forName(className.replace('/', '.'));
            } catch (ClassNotFoundException e) {
                throw new cn.warriorview.script.core.ScriptCompileException(
                        "instanceof check references unknown class: " + className);
            }
        }

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

            // 缺口2：若 value 仍为字符串且操作符为数值类，尝试作为数学表达式解析
            if (value instanceof String mathStr && isNumericOp(op)) {
                try {
                    cn.warriorview.script.math.MathNode mathNode = cn.warriorview.script.math.MathParser.parse(mathStr);
                    if (mathNode instanceof cn.warriorview.script.math.MathNode.LiteralNode lit) {
                        // 纯常量表达式（如 "5*3+2"）——直接折叠为数值
                        value = lit.value();
                    } else {
                        // 含变量的表达式（如 "{maxHp} * 0.5"）——存储 MathNode 供运行时发射
                        attrs.put("valueNode", mathNode);
                    }
                } catch (IllegalArgumentException ignored) {
                    // 解析失败则保持原始字符串
                }
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

        // 根据方法的实际返回类型决定 return 指令（避免 void RETURN 在 Object 方法中非法）
        emitEarlyReturn(mv, ctx);
        mv.visitLabel(continueLabel);

        // instanceof 成功路径：将变量窄化为目标类型，供后续节点使用。
        // 只处理面向顺序流的顶层 emit （非复合条件内部）；取反的 !instanceof 不注册。
        FlowNode conditionAction = node.getAttrOrDefault("conditionAction", null);
        if (conditionAction == null) {
            OpInfo info = parseOp(node);
            if ("instanceof".equals(info.op()) && !info.negate()) {
                String variable = node.getRequiredAttr("variable");
                String rawClass = node.<String>getRequiredAttr("value").replace('/', '.');
                try {
                    ctx.narrowType(variable, Class.forName(rawClass));
                } catch (ClassNotFoundException e) {
                    // parse 阶段已验证，这里不应到达
                    throw new cn.warriorview.script.core.ScriptCompileException(
                            "[instanceof narrow] class not found at emit: " + rawClass);
                }
            }
        }
    }

    /**
     * 根据上下文目标返回类型发射早退 return 指令。
     * <p>
     * void 方法发 RETURN；Object/Array 方法先 ACONST_NULL 再 ARETURN；原生类型方法发对应零值。
     */
    static void emitEarlyReturn(MethodVisitor mv, CompilationContext ctx) {
        org.objectweb.asm.Type ret = ctx.targetReturnType();
        if (ret.getSort() == org.objectweb.asm.Type.VOID) {
            mv.visitInsn(Opcodes.RETURN);
        } else if (ret.getSort() == org.objectweb.asm.Type.OBJECT
                || ret.getSort() == org.objectweb.asm.Type.ARRAY) {
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitInsn(Opcodes.ARETURN);
        } else if (ret.getSort() == org.objectweb.asm.Type.DOUBLE) {
            mv.visitInsn(Opcodes.DCONST_0);
            mv.visitInsn(Opcodes.DRETURN);
        } else if (ret.getSort() == org.objectweb.asm.Type.LONG) {
            mv.visitInsn(Opcodes.LCONST_0);
            mv.visitInsn(Opcodes.LRETURN);
        } else if (ret.getSort() == org.objectweb.asm.Type.FLOAT) {
            mv.visitInsn(Opcodes.FCONST_0);
            mv.visitInsn(Opcodes.FRETURN);
        } else {
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitInsn(Opcodes.IRETURN);
        }
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
        // op 可能在优化器下沉后仍保留于节点属性中（conditionAction != null 时 variable 被移除，但 op/value 保留）
        String rawOp = node.getAttrOrDefault("op", null);
        String op = null;
        boolean negate = false;
        if (rawOp != null) {
            OpInfo info = parseOp(rawOp);
            op = info.op();
            negate = info.negate();
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

                jumpOp = emitSinkingCheck(mv, op, tempSlot, exactType, node, ctx);
            } else {
                conditionAction.type().handler().emit(conditionAction, mv, ctx);
                if (conditionAction.type() == FlowNodeType.MATH) {
                    jumpOp = emitDoubleComparisonOnStack(mv, node, op != null ? op : "==", ctx);
                } else {
                    jumpOp = Opcodes.IFNE;
                }
            }
        } else {
            String variable = node.getRequiredAttr("variable");
            int slot = ctx.getSlot(variable);
            IRType type = ctx.getType(variable);

            validateOpType(op, variable, type);

            jumpOp = emitSinkingCheck(mv, op, slot, type, node, ctx);
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
                                "Operator '%s' requires a numeric type (INT/LONG/DOUBLE), but variable '%s' is of type %s. "
                                        + "Hint: use '==' for equality or 'contains' for collection membership.",
                                op, variable, type));
            }
        } else if ("starts_with".equals(op) || "ends_with".equals(op) || "matches".equals(op)) {
            if (type != IRType.STRING) {
                throw new cn.warriorview.script.core.ScriptCompileException(
                        String.format(
                                "Operator '%s' requires a STRING type, but variable '%s' is of type %s. "
                                        + "Hint: use '==' for non-string equality checks.",
                                op, variable, type));
            }
        } else if ("contains".equals(op)) {
            if (type != IRType.STRING && type.base() != IRType.COLLECTION.base()) {
                throw new cn.warriorview.script.core.ScriptCompileException(
                        String.format(
                                "Operator '%s' requires a STRING or COLLECTION type, but variable '%s' is of type %s. "
                                        + "Hint: for numeric ranges, use 'between'; for set membership, use 'in'.",
                                op, variable, type));
            }
        } else if ("in".equals(op)) {
            // 白名单：仅 STRING、INT、ENUM 支持 in 操作（集合成员判定）
            if (type != IRType.STRING && type != IRType.INT && type != IRType.ENUM) {
                String hint = (type == IRType.DOUBLE || type == IRType.LONG)
                        ? "Hint: use 'between' for numeric range checks, or '==' for exact equality."
                        : (type == IRType.BOOLEAN)
                                ? "Hint: boolean variables should use '== true' or '== false' directly."
                                : "Hint: 'in' only supports STRING/INT/ENUM. Use '==' for equality or 'contains' for collection membership.";
                throw new cn.warriorview.script.core.ScriptCompileException(
                        String.format(
                                "Operator 'in' is not supported for type %s on variable '%s'. %s",
                                type, variable, hint));
            }
        } else if ("==".equals(op) && type == IRType.COLLECTION) {
            throw new cn.warriorview.script.core.ScriptCompileException(
                    String.format(
                            "Operator '==' on COLLECTION variable '%s' compares by reference, which is almost certainly not what you want. "
                                    + "Hint: did you mean 'contains' to check membership?",
                            variable));
        }
    }

    // ======================== null ========================

    private int emitSinkingCheck(MethodVisitor mv, String op, int slot, IRType type, FlowNode node,
            CompilationContext ctx) {
        return switch (op) {
            case "null" -> emitNullCheck(mv, slot);
            case "instanceof" -> emitInstanceof(mv, slot, node);
            case "contains" -> emitContains(mv, slot, node, type);
            case "starts_with" -> emitStringOp(mv, "startsWith", slot, node);
            case "ends_with" -> emitStringOp(mv, "endsWith", slot, node);
            case "matches" -> emitMatches(mv, slot, node);
            case "in" -> emitIn(mv, slot, node, type);
            case "between" -> emitBetween(mv, slot, node, type);
            default -> emitComparison(mv, slot, type, op, node, ctx);
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

    private int emitComparison(MethodVisitor mv, int slot, IRType type, String op, FlowNode node,
            CompilationContext ctx) {
        if (type == IRType.BOOLEAN) {
            return emitBooleanComparison(mv, slot, node, op);
        } else if (type == IRType.DOUBLE) {
            return emitDoubleComparison(mv, slot, node, op, ctx);
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
    private int emitDoubleComparison(MethodVisitor mv, int slot, FlowNode node, String op,
            CompilationContext ctx) {
        mv.visitVarInsn(Opcodes.DLOAD, slot);
        return emitDoubleComparisonOnStack(mv, node, op, ctx);
    }

    private int emitDoubleComparisonOnStack(MethodVisitor mv, FlowNode node, String op,
            CompilationContext ctx) {
        // 缺口2：若有 valueNode（含变量的数学表达式），发射其字节码而非常量
        cn.warriorview.script.math.MathNode valueNode = node.getAttrOrDefault("valueNode", null);
        if (valueNode != null) {
            MathNodeHandler.emitMathNode(valueNode, mv, ctx);
        } else {
            cn.warriorview.script.codegen.ASMUtils.emitDoubleConst(mv, node.numericValue());
        }
        mv.visitInsn(Opcodes.DCMPG);
        return cmpJump(op);
    }

    /** int 零装箱比较 */
    private int emitIntComparison(MethodVisitor mv, int slot, FlowNode node, String op) {
        mv.visitVarInsn(Opcodes.ILOAD, slot);
        ASMUtils.emitIntConst(mv, (int) node.numericValue());
        return intCmpJump(op);
    }

    /** long 比较 */
    private int emitLongComparison(MethodVisitor mv, int slot, FlowNode node, String op) {
        mv.visitVarInsn(Opcodes.LLOAD, slot);
        ASMUtils.emitLongConst(mv, (long) node.numericValue());
        mv.visitInsn(Opcodes.LCMP);
        return cmpJump(op);
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
        String op = parseOp(rawOp).op();
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
        // op 已由 parseOp 剥离 ! 前缀，"null" 是唯一可能到达此处的值
        if ("null".equals(op) && range.nonNull())
            return Boolean.FALSE;
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

    private static OpInfo parseOp(String rawOp) {
        boolean negate = rawOp.startsWith("!");
        return new OpInfo(negate ? rawOp.substring(1) : rawOp, negate);
    }

    private static OpInfo parseOp(FlowNode node) {
        return parseOp(node.<String>getRequiredAttr("op"));
    }

    /** DCMPG/LCMP 后的单值比较跳转 opcode（double/long 共用）。 */
    private static int cmpJump(String op) {
        return switch (op) {
            case ">" -> Opcodes.IFGT;
            case ">=" -> Opcodes.IFGE;
            case "<" -> Opcodes.IFLT;
            case "<=" -> Opcodes.IFLE;
            case "==" -> Opcodes.IFEQ;
            default -> throw new IllegalArgumentException("Unsupported comparison op: " + op);
        };
    }

    /** 双栈值 int 比较跳转 opcode。 */
    private static int intCmpJump(String op) {
        return switch (op) {
            case ">" -> Opcodes.IF_ICMPGT;
            case ">=" -> Opcodes.IF_ICMPGE;
            case "<" -> Opcodes.IF_ICMPLT;
            case "<=" -> Opcodes.IF_ICMPLE;
            case "==" -> Opcodes.IF_ICMPEQ;
            default -> throw new IllegalArgumentException("Unsupported comparison op for int: " + op);
        };
    }

    /** 操作符是否为数值类比较（决定 value 字段是否可尝试作为数学表达式解析）。 */
    private static boolean isNumericOp(String op) {
        if (op == null) return false;
        return switch (parseOp(op).op()) {
            case ">", ">=", "<", "<=", "==", "between" -> true;
            default -> false;
        };
    }

    /** 覆写以包含 valueNode 中引用的变量（活跃变量分析需要）。 */
    @Override
    public java.util.List<String> getAllConsumedVariables(FlowNode node) {
        java.util.List<String> vars = new ArrayList<>();
        String main = getConsumedVariable(node);
        if (main != null) vars.add(main);
        cn.warriorview.script.math.MathNode valueNode = node.getAttrOrDefault("valueNode", null);
        if (valueNode != null) vars.addAll(cn.warriorview.script.math.MathNode.collectVarNames(valueNode));
        return vars;
    }
}
