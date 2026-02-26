package cn.warriorview.script.handler;

import cn.warriorview.script.codegen.ASMUtils;

import cn.warriorview.script.action.ActionRegistry;
import cn.warriorview.script.codegen.BytecodeCompiler;
import cn.warriorview.script.core.CompilationContext;
import cn.warriorview.script.core.ScriptIR;
import cn.warriorview.script.core.ScriptIR.FlowNode;
import cn.warriorview.script.core.ScriptIR.FlowNodeType;
import cn.warriorview.script.core.ScriptIR.NodeCapability;
import cn.warriorview.script.core.ScriptIR.IRType;
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
public final class ActionNodeHandler implements ScriptIR.FlowNodeHandler, ScriptIR.VariableProducer {

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
    public FlowNode parse(Map<String, Object> yaml) {
        String action = (String) yaml.get("action");
        String store = (String) yaml.get("store");

        @SuppressWarnings("unchecked")
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
            IRType reqIRType = IRType.fromClass(reqType);

            if (reqIRType.isNumeric()) {
                Object parsed = cn.warriorview.script.parser.ScriptParser.ValueParser.parseNumber(argStr);
                boolean isNumber = (parsed instanceof Number);
                if (!isNumber && !argStr.matches("-?\\d+(\\.\\d+)?")) {
                    throw new cn.warriorview.script.core.ScriptCompileException(
                            String.format("Action '%s' expects a numeric value at argument %d (type %s), but got '%s'.",
                                    action, methodParamIndex, reqType.getSimpleName(), argStr));
                }
            } else if (reqIRType == IRType.BOOLEAN) {
                if (!argStr.equalsIgnoreCase("true") && !argStr.equalsIgnoreCase("false")) {
                    throw new cn.warriorview.script.core.ScriptCompileException(
                            String.format("Action '%s' expects a boolean (true/false) at argument %d, but got '%s'.",
                                    action, methodParamIndex, argStr));
                }
            }
        }

        // 验证 store (不能存 void)
        if (store != null) {
            if (def.returnType() == void.class || def.returnType() == Void.class) {
                throw new cn.warriorview.script.core.ScriptCompileException(
                        String.format("Action '%s' does not return a value, cannot store to '%s'", action, store));
            }
        }

        IRType returnIRType = IRType.fromClass(def.returnType());

        ImmutableMap.Builder<String, Object> attrs = ImmutableMap.builder();
        attrs.put("action", action);
        attrs.put("args", ImmutableList.copyOf(args));
        attrs.put("def", def);
        if (store != null) {
            attrs.put("store", store);
            attrs.put("returnType", returnIRType);
        }

        return new FlowNode(FlowNodeType.ACTION, attrs.build());
    }

    @Override
    public void emit(FlowNode node, MethodVisitor mv, CompilationContext ctx) {
        ImmutableList<String> args = node.getRequiredAttr("args");
        ActionRegistry.ActionDef def = node.getRequiredAttr("def");
        String store = node.getAttrOrDefault("store", null);

        // 统一分发：加载参数 → 调用方法
        emitActionCall(mv, def, args, ctx);

        // 处理返回值栈平衡与保存
        Class<?> retClass = def.returnType();
        boolean hasReturn = (retClass != void.class && retClass != Void.class);

        if (hasReturn) {
            if (store != null) {
                int slot = ctx.getSlot(store);
                int storeOpcode = org.objectweb.asm.Type.getType(retClass).getOpcode(Opcodes.ISTORE);
                mv.visitVarInsn(storeOpcode, slot);
            } else {
                // 未被 store 但方法返回了值，必须 POP 清理栈避免 VerifyError
                int popOpcode = org.objectweb.asm.Type.getType(retClass).getSize() == 2 ? Opcodes.POP2 : Opcodes.POP;
                mv.visitInsn(popOpcode);
            }
        }
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

        Class<?>[] pTypes = def.paramTypes();

        // 加载后续参数
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            int methodParamIndex = i + 1;
            Class<?> reqType = pTypes[methodParamIndex];

            if (ScriptParser.ValueParser.isTemplate(arg)) {
                // 模板字符串 → invokedynamic StringConcatFactory
                BytecodeCompiler.emitStringConcat(mv, arg, ctx);
            } else {
                Class<?> unwrappedType = com.google.common.primitives.Primitives.unwrap(reqType);
                if (unwrappedType.isEnum()) {
                    // 枚举自动寻址
                    mv.visitFieldInsn(Opcodes.GETSTATIC, org.objectweb.asm.Type.getInternalName(unwrappedType),
                            arg.toUpperCase(), org.objectweb.asm.Type.getDescriptor(unwrappedType));
                } else if (unwrappedType.isPrimitive()) {
                    // 直接发射原始类型常量，无装箱需求
                    Object parsed = unwrappedType == boolean.class
                            ? Boolean.parseBoolean(arg)
                            : ScriptParser.ValueParser.parseNumber(arg);
                    ASMUtils.emitPrimitiveLiteral(mv, parsed, unwrappedType);
                } else {
                    // String 或包装类型：直接 LDC
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

    @Override
    public String getProducedVariable(FlowNode node) {
        return node.getAttrOrDefault("store", null);
    }

    @Override
    public FlowNode createVirtualProducer(ScriptIR.VarDecl decl) {
        return new FlowNode(FlowNodeType.ACTION,
                ImmutableMap.of(
                        "_sinking_property", decl.property(),
                        "returnType", decl.type()));
    }
}
