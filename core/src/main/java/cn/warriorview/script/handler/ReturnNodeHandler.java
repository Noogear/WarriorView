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
            emitVariable(mv, ctx, varName);
            mv.visitInsn(Opcodes.ARETURN);
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

                if (returnType.isPrimitive()) {
                    ASMUtils.emitBox(mv, returnType);
                }

                mv.visitInsn(Opcodes.ARETURN);
                return;
            }

            // 路径4：空返回
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitInsn(Opcodes.ARETURN);
            return;
        }

        // 路径5：集合
        if (value instanceof List<?> list) {
            emitList(mv, ctx, list);
            mv.visitInsn(Opcodes.ARETURN);
            return;
        }

        // 路径2/3：字符串值 — 区分单变量、模板、字面量
        if (value instanceof String strVal) {
            if (ScriptIR.isSingleVar(strVal)) {
                // 路径2a："{dmg}" → 变量路径
                String singleVarName = strVal.substring(1, strVal.length() - 1);
                if (ctx.getSlot(singleVarName) >= 0) {
                    emitVariable(mv, ctx, singleVarName);
                    mv.visitInsn(Opcodes.ARETURN);
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
            ASMUtils.emitLiteral(mv, strVal);
            mv.visitInsn(Opcodes.ARETURN);
            return;
        }

        // 路径3：数字/布尔字面量
        ASMUtils.emitLiteral(mv, value);
        mv.visitInsn(Opcodes.ARETURN);
    }

    // ── 工具方法 ────────────────────────────────────────

    // ── 工具方法 ────────────────────────────────────────

    /** 发射变量加载 + 原始类型装箱，结果始终为 Object。 */
    private static void emitVariable(MethodVisitor mv, CompilationContext ctx, String varName) {
        ASMUtils.emitLoadBoxed(mv, ctx.getSlot(varName), ctx.getType(varName));
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
