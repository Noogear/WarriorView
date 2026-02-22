package cn.warriorview.script.handler;

import cn.warriorview.script.action.ActionRegistry;
import cn.warriorview.script.codegen.BytecodeCompiler;
import cn.warriorview.script.core.CompilationContext;
import cn.warriorview.script.core.ScriptIR;
import cn.warriorview.script.core.ScriptIR.FlowNode;
import cn.warriorview.script.core.ScriptIR.FlowNodeType;
import cn.warriorview.script.core.ScriptIR.NodeCapability;
import cn.warriorview.script.parser.ScriptParser;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/**
 * ACTION 节点处理器。
 * <p>
 * 统一通过 {@link ActionRegistry.ActionDef} 分发所有动作调用，
 * 不再对特定动作做硬编码 switch 特殊处理。
 * 字符串模板使用 {@code invokedynamic StringConcatFactory}。
 */
@SuppressWarnings("null")
public final class ActionNodeHandler implements ScriptIR.FlowNodeHandler {

    private static final ActionRegistry REGISTRY = new ActionRegistry();

    static {
        FlowNodeType.registerHandler(FlowNodeType.ACTION, ActionNodeHandler::new);
    }

    public static void init() {
    }

    public static ActionRegistry registry() {
        return REGISTRY;
    }

    @Override
    @SuppressWarnings("unchecked")
    public FlowNode parse(Map<String, Object> yaml) {
        String action = (String) yaml.get("action");
        List<String> args = (List<String>) yaml.getOrDefault("args", List.of());

        // 验证动作存在
        ActionRegistry.ActionDef def = REGISTRY.lookup(action);

        // 前端校验：参数个数（目标方法如果有实体/Event等附带参数，第一位往往由系统传入，YAML参数为其后内容）
        // 假定：所有的 @ScriptAction 方法，第一个参数都是 Payload/Event/Entity（上下文自动推断），后续的则是 YAML 提供
        int expectedArgs = def.paramCount() - 1;
        if (expectedArgs < 0)
            expectedArgs = 0; // 无参方法？（虽然很少，但兼容）

        if (args.size() != expectedArgs) {
            throw new cn.warriorview.script.core.ScriptCompileException(
                    String.format("Action '%s' expects %d arguments, but got %d.", action, expectedArgs, args.size()));
        }

        // 类型静态校验（简单推测验证：数字型强转）
        Class<?>[] pTypes = def.paramTypes();
        for (int i = 0; i < args.size(); i++) {
            String argStr = args.get(i);
            int methodParamIndex = i + 1;

            // 跳过包含模板变量的参数（因为在运行时拼接，暂时无法纯静态检查）
            if (cn.warriorview.script.parser.ScriptParser.ValueParser.isTemplate(argStr)) {
                continue;
            }

            Class<?> reqType = pTypes[methodParamIndex];
            if (reqType == int.class || reqType == Integer.class ||
                    reqType == long.class || reqType == Long.class ||
                    reqType == float.class || reqType == Float.class ||
                    reqType == double.class || reqType == Double.class) {

                Object parsed = cn.warriorview.script.parser.ScriptParser.ValueParser.parseNumber(argStr);
                boolean isNumber = (parsed instanceof Number);
                if (!isNumber && !argStr.matches("-?\\d+(\\.\\d+)?")) {
                    throw new cn.warriorview.script.core.ScriptCompileException(
                            String.format("Action '%s' expects a numeric value at argument %d (type %s), but got '%s'.",
                                    action, methodParamIndex, reqType.getSimpleName(), argStr));
                }
            } else if (reqType == boolean.class || reqType == Boolean.class) {
                if (!argStr.equalsIgnoreCase("true") && !argStr.equalsIgnoreCase("false")) {
                    throw new cn.warriorview.script.core.ScriptCompileException(
                            String.format("Action '%s' expects a boolean (true/false) at argument %d, but got '%s'.",
                                    action, methodParamIndex, argStr));
                }
            }
        }

        ImmutableMap.Builder<String, Object> attrs = ImmutableMap.builder();
        attrs.put("action", action);
        attrs.put("args", ImmutableList.copyOf(args));
        attrs.put("def", def);

        return new FlowNode(FlowNodeType.ACTION, attrs.build());
    }

    @Override
    public void emit(FlowNode node, MethodVisitor mv, CompilationContext ctx) {
        ImmutableList<String> args = node.attr("args");
        ActionRegistry.ActionDef def = node.attr("def");

        // 统一分发：加载参数 → 调用方法
        emitActionCall(mv, def, args, ctx);
    }

    /**
     * 统一动作调用发射。
     * <p>
     * 根据 ActionDef 的参数类型智能加载参数，
     * 字符串模板使用 invokedynamic StringConcatFactory。
     */
    private void emitActionCall(MethodVisitor mv, ActionRegistry.ActionDef def,
            ImmutableList<String> args, CompilationContext ctx) {
        // 加载 event 参数（slot 1）作为第一个方法参数
        mv.visitVarInsn(Opcodes.ALOAD, 1);

        // 加载后续参数
        for (String arg : args) {
            if (ScriptParser.ValueParser.isTemplate(arg)) {
                // 模板字符串 → invokedynamic StringConcatFactory
                BytecodeCompiler.emitStringConcat(mv, arg, ctx);
            } else {
                Object parsed = ScriptParser.ValueParser.parseNumber(arg);
                if (parsed instanceof Integer i) {
                    BytecodeCompiler.emitIntConst(mv, i);
                } else if (parsed instanceof Long l) {
                    BytecodeCompiler.emitLongConst(mv, l);
                } else if (parsed instanceof Double d) {
                    BytecodeCompiler.emitDoubleConst(mv, d);
                } else {
                    mv.visitLdcInsn(arg);
                }
            }
        }

        // 调用目标方法
        boolean isInterface = def.invokeType() == Opcodes.INVOKEINTERFACE;
        mv.visitMethodInsn(def.invokeType(), def.owner(), def.method(),
                def.descriptor(), isInterface);
    }

    @Override
    public EnumSet<NodeCapability> capabilities() {
        return EnumSet.of(NodeCapability.SIDE_EFFECT);
    }
}
