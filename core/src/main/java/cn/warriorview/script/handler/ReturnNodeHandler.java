package cn.warriorview.script.handler;

import cn.warriorview.script.codegen.ASMUtils;
import cn.warriorview.script.codegen.BytecodeCompiler;
import cn.warriorview.script.core.CompilationContext;
import cn.warriorview.script.core.ScriptIR;
import cn.warriorview.script.core.ScriptIR.FlowNode;
import cn.warriorview.script.core.ScriptIR.FlowNodeType;
import cn.warriorview.script.core.ScriptIR.NodeCapability;
import com.google.common.collect.ImmutableMap;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/**
 * RETURN / RETURN_VALUE 统一节点处理器。
 *
 * <p>
 * 支持五种 emit 路径：
 * <ol>
 * <li><b>无返回值</b>：{@code - return} → {@code ACONST_NULL + ARETURN}</li>
 * <li><b>变量返回</b>：{@code - return: "{dmg}"} →
 * {@code XLOAD + [装箱] + ARETURN}</li>
 * <li><b>模板字符串</b>：{@code - return: "HP:{hp} 伤:{dmg}"} → invokedynamic +
 * ARETURN</li>
 * <li><b>字面量</b>：{@code - return: 42 / true / "文本"} →
 * {@link ASMUtils#emitLiteral} + ARETURN</li>
 * <li><b>集合字面量</b>：{@code - return: [1, "{hp}", true]} → 逐元素发射 +
 * {@code List.of} + ARETURN</li>
 * </ol>
 *
 * <p>
 * YAML 示例：
 * 
 * <pre>{@code
 * - return
 * - return: 42
 * - return: true
 * - return: "{dmg}"
 * - return: "HP:{hp} 伤:{dmg}"
 * - return: [1, "{hp}", "fixed"]
 * }</pre>
 */
public final class ReturnNodeHandler implements cn.warriorview.script.core.ScriptIR.FlowNodeHandler,
        cn.warriorview.script.core.ScriptIR.VariableConsumer, cn.warriorview.script.core.ScriptIR.NodeTraverser {

    static {
        FlowNodeType.registerHandler(FlowNodeType.RETURN, ReturnNodeHandler::new);
    }

    public static void init() {
    }

    @Override
    public FlowNode parse(Map<String, Object> yaml) {
        // 短语法：- return: xxx
        Object shortValue = yaml.get("return");
        if (shortValue != null) {
            return new FlowNode(FlowNodeType.RETURN, ImmutableMap.of("value", shortValue));
        }

        Object standardValue = yaml.get("value");
        Object variable = yaml.get("variable");

        if (standardValue != null && variable != null) {
            // 如果同时提供了 value 和 variable，包装为集合 ["{variable}", value]
            return new FlowNode(FlowNodeType.RETURN,
                    ImmutableMap.of("value", List.of("{" + variable + "}", standardValue)));
        }

        if (standardValue != null) {
            return new FlowNode(FlowNodeType.RETURN, ImmutableMap.of("value", standardValue));
        }

        if (variable != null) {
            return new FlowNode(FlowNodeType.RETURN, ImmutableMap.of("variable", variable.toString()));
        }

        return new FlowNode(FlowNodeType.RETURN, ImmutableMap.of());
    }

    @Override
    public void emit(FlowNode node, MethodVisitor mv, CompilationContext ctx) {
        // 路径1：旧格式 variable attr（由 ScriptBuilder.returnVar 注入）
        String varName = node.getAttrOrDefault("variable", null);
        if (varName != null) {
            emitTargetVariableReturn(mv, ctx, varName);
            return;
        }

        Object value = node.getAttrOrDefault("value", null);
        if (value == null) {
            // Check for sinking hooked dummy producer
            FlowNode conditionAction = node.getAttrOrDefault("conditionAction", null);
            if (conditionAction != null) {
                // Sunk property hook
                String sinkingProp = conditionAction.getRequiredAttr("_sinking_property");
                cn.warriorview.script.core.ScriptIR.IRType returnType = conditionAction.getRequiredAttr("returnType");

                BytecodeCompiler.emitSunkPropertyLoad(mv, ctx, sinkingProp);

                // 按目标接口自适应返回指令，不无脑 ARETURN
                emitAdaptiveReturn(mv, ctx, returnType);
                return;
            }

            // 路径4：空返回 (null / 0)
            emitZeroReturn(mv, ctx);
            return;
        }

        // 路径5：集合
        if (value instanceof List<?> list) {
            emitList(mv, ctx, list); // emits ALOAD (Array)
            mv.visitInsn(Opcodes.ARETURN);
            return;
        }

        // 路径2/3：字符串值 — 区分单变量、模板、字面量
        if (value instanceof String strVal) {
            if (ScriptIR.isSingleVar(strVal)) {
                // 路径2a："{dmg}" → 变量路径
                String singleVarName = strVal.substring(1, strVal.length() - 1);
                if (ctx.getSlot(singleVarName) >= 0) {
                    emitTargetVariableReturn(mv, ctx, singleVarName);
                    return;
                }
            }
            if (ScriptIR.isTemplate(strVal)) {
                // 路径2b："HP:{hp} 伤:{dmg}" → invokedynamic 模板
                BytecodeCompiler.emitStringConcat(mv, strVal, ctx);
                mv.visitInsn(Opcodes.ARETURN);
                return;
            }
            // 路径3：纯字符串字面量
            ASMUtils.emitLiteral(mv, strVal); // String is reference
            mv.visitInsn(Opcodes.ARETURN);
            return;
        }

        // 路径3：数字/布尔字面量
        org.objectweb.asm.Type tType = ctx.targetReturnType();
        if (tType.getSort() == org.objectweb.asm.Type.OBJECT || tType.getSort() == org.objectweb.asm.Type.ARRAY) {
            ASMUtils.emitLiteral(mv, value); // emit boxed
            mv.visitInsn(Opcodes.ARETURN);
        } else {
            // 极速路径：直接将数字常量发射为原生栈帧，再触发原始返回
            emitNativeLiteral(mv, value, tType);
            mv.visitInsn(tType.getOpcode(Opcodes.IRETURN));
        }
    }

    // ── 工具方法 ────────────────────────────────────────

    /** 根据目标接口确切要求，返回原生类型自适应指令。 */
    private static void emitAdaptiveReturn(MethodVisitor mv, CompilationContext ctx,
            cn.warriorview.script.core.ScriptIR.IRType varType) {
        org.objectweb.asm.Type tType = ctx.targetReturnType();

        if (tType.getSort() == org.objectweb.asm.Type.VOID) {
            if (varType.isPrimitive()) {
                // 弹出没用的原始值（单字或双字）
                if (varType.base() == ScriptIR.BaseType.DOUBLE || varType.base() == ScriptIR.BaseType.LONG) {
                    mv.visitInsn(Opcodes.POP2);
                } else {
                    mv.visitInsn(Opcodes.POP);
                }
            } else {
                mv.visitInsn(Opcodes.POP); // 弹出引用
            }
            mv.visitInsn(Opcodes.RETURN);
            return;
        }

        if (tType.getSort() == org.objectweb.asm.Type.OBJECT || tType.getSort() == org.objectweb.asm.Type.ARRAY) {
            if (varType.isPrimitive()) {
                ASMUtils.emitBox(mv, varType);
            }
            mv.visitInsn(Opcodes.ARETURN);
            return;
        }

        // 否则必定为原生返回目标，且依据校验管道已通过可赋值检验。我们直接以原生的 return opcode 退出。
        mv.visitInsn(tType.getOpcode(Opcodes.IRETURN));
    }

    /** 针对明确的变量发射，如果需要装箱则装，如果是原生则按原始返回。 */
    private static void emitTargetVariableReturn(MethodVisitor mv, CompilationContext ctx, String varName) {
        org.objectweb.asm.Type tType = ctx.targetReturnType();
        ScriptIR.IRType varType = ctx.getType(varName);
        int slot = ctx.getSlot(varName);

        if (tType.getSort() == org.objectweb.asm.Type.OBJECT || tType.getSort() == org.objectweb.asm.Type.ARRAY) {
            ASMUtils.emitLoadBoxed(mv, slot, varType);
            mv.visitInsn(Opcodes.ARETURN);
        } else {
            // 直接原始指令压栈
            switch (varType.base()) {
                case INT:
                case BOOLEAN:
                    mv.visitVarInsn(Opcodes.ILOAD, slot);
                    break;
                case LONG:
                    mv.visitVarInsn(Opcodes.LLOAD, slot);
                    break;
                case DOUBLE:
                    mv.visitVarInsn(Opcodes.DLOAD, slot);
                    break;
                default:
                    throw new IllegalStateException("Trying to return Object unboxed natively.");
            }
            mv.visitInsn(tType.getOpcode(Opcodes.IRETURN));
        }
    }

    /** 始终发射变量加载 + 装箱（如 List 元素加载） */
    private static void emitVariable(MethodVisitor mv, CompilationContext ctx, String varName) {
        ASMUtils.emitLoadBoxed(mv, ctx.getSlot(varName), ctx.getType(varName));
    }

    private static void emitNativeLiteral(MethodVisitor mv, Object parsed, org.objectweb.asm.Type tType) {
        if (tType.getSort() == org.objectweb.asm.Type.BOOLEAN) {
            mv.visitInsn(((Boolean) parsed) ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
        } else if (tType.getSort() == org.objectweb.asm.Type.INT || tType.getSort() == org.objectweb.asm.Type.SHORT
                || tType.getSort() == org.objectweb.asm.Type.BYTE) {
            ASMUtils.emitIntConst(mv, ((Number) parsed).intValue());
        } else if (tType.getSort() == org.objectweb.asm.Type.LONG) {
            ASMUtils.emitLongConst(mv, ((Number) parsed).longValue());
        } else if (tType.getSort() == org.objectweb.asm.Type.DOUBLE) {
            ASMUtils.emitDoubleConst(mv, ((Number) parsed).doubleValue());
        } else if (tType.getSort() == org.objectweb.asm.Type.FLOAT) {
            ASMUtils.emitFloatConst(mv, ((Number) parsed).floatValue());
        } else {
            throw new IllegalArgumentException("emitNativeLiteral unsupported: " + tType);
        }
    }

    private static void emitZeroReturn(MethodVisitor mv, CompilationContext ctx) {
        org.objectweb.asm.Type retType = ctx.targetReturnType();
        if (retType.getSort() == org.objectweb.asm.Type.VOID) {
            mv.visitInsn(Opcodes.RETURN);
        } else if (retType.getSort() == org.objectweb.asm.Type.OBJECT
                || retType.getSort() == org.objectweb.asm.Type.ARRAY) {
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitInsn(Opcodes.ARETURN);
        } else if (retType.getSort() == org.objectweb.asm.Type.DOUBLE) {
            mv.visitInsn(Opcodes.DCONST_0);
            mv.visitInsn(Opcodes.DRETURN);
        } else if (retType.getSort() == org.objectweb.asm.Type.FLOAT) {
            mv.visitInsn(Opcodes.FCONST_0);
            mv.visitInsn(Opcodes.FRETURN);
        } else if (retType.getSort() == org.objectweb.asm.Type.LONG) {
            mv.visitInsn(Opcodes.LCONST_0);
            mv.visitInsn(Opcodes.LRETURN);
        } else {
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitInsn(Opcodes.IRETURN);
        }
    }

    /**
     * 发射 List 字面量：逐元素按规则发射，末尾调用 {@code List.of(Object...)}。
     * 每个元素支持：字面量 / 单变量 / 模板字符串。
     */
    private static void emitList(MethodVisitor mv, CompilationContext ctx, List<?> list) {
        // 创建 Object 数组
        ASMUtils.emitIntConst(mv, list.size());
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");

        for (int i = 0; i < list.size(); i++) {
            mv.visitInsn(Opcodes.DUP);
            ASMUtils.emitIntConst(mv, i);
            Object elem = list.get(i);
            emitSingleElement(mv, ctx, elem);
            mv.visitInsn(Opcodes.AASTORE);
        }

        // List.of(Object...) varargs
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/List", "of",
                "([Ljava/lang/Object;)Ljava/util/List;", true);
    }

    private static void emitSingleElement(MethodVisitor mv, CompilationContext ctx, Object elem) {
        if (elem instanceof String s) {
            if (ScriptIR.isSingleVar(s)) {
                String varName = s.substring(1, s.length() - 1);
                if (ctx.getSlot(varName) >= 0) {
                    emitVariable(mv, ctx, varName);
                    return;
                }
            }
            if (ScriptIR.isTemplate(s)) {
                BytecodeCompiler.emitStringConcat(mv, s, ctx);
                return;
            }
        }
        ASMUtils.emitLiteral(mv, elem);
    }

    @Override
    public EnumSet<NodeCapability> capabilities() {
        return EnumSet.of(NodeCapability.TERMINATES_FLOW);
    }

    @Override
    public String getConsumedVariable(FlowNode node) {
        String varName = node.getAttrOrDefault("variable", null);
        if (varName != null) {
            return varName;
        }

        Object value = node.getAttrOrDefault("value", null);
        if (value instanceof String strVal && ScriptIR.isSingleVar(strVal)) {
            return strVal.substring(1, strVal.length() - 1);
        }

        return null; // Not a primitive single variable return, don't sink
    }

    @Override
    public Iterable<FlowNode> traverseChildren(FlowNode node) {
        FlowNode conditionAction = node.getAttrOrDefault("conditionAction", null);
        if (conditionAction != null) {
            return List.of(conditionAction);
        }
        return List.of();
    }
}
