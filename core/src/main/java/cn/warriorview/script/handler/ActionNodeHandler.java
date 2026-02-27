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
public final class ActionNodeHandler implements ScriptIR.FlowNodeHandler, ScriptIR.VariableProducer,
        ScriptIR.VariableConsumer, ScriptIR.NodeTraverser, ScriptIR.TypeValidator {

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
            if (ScriptIR.isTemplate(argStr)) {
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
            } else if (reqIRType == IRType.ENUM) {
                try {
                    @SuppressWarnings({ "unchecked", "rawtypes", "unused" })
                    Object ignored = Enum.valueOf((Class<Enum>) reqType, argStr);
                } catch (IllegalArgumentException e) {
                    throw new cn.warriorview.script.core.ScriptCompileException(
                            String.format(
                                    "Action '%s' expects an enum value of %s at argument %d, but got invalid constant '%s'.",
                                    action, reqType.getSimpleName(), methodParamIndex, argStr));
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
        emitActionCall(mv, def, args, ctx, node);

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
            ImmutableList<String> args, CompilationContext ctx, FlowNode node) {
        // 加载 event 参数（slot 1）作为第一个方法参数
        mv.visitVarInsn(Opcodes.ALOAD, 1);

        Class<?>[] pTypes = def.paramTypes();

        // Load arguments
        FlowNode conditionAction = node.getAttrOrDefault("conditionAction", null);
        int sinkArgIndex = node.getAttrOrDefault("_sink_arg_index", -1);

        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            int methodParamIndex = i + 1;
            Class<?> reqType = pTypes[methodParamIndex];

            if (i == sinkArgIndex && conditionAction != null) {
                // Sunk property argument
                String sinkingProp = conditionAction.getRequiredAttr("_sinking_property");
                IRType returnType = conditionAction.getRequiredAttr("returnType");

                BytecodeCompiler.emitSunkPropertyLoad(mv, ctx, sinkingProp);

                Class<?> unwrappedType = com.google.common.primitives.Primitives.unwrap(reqType);
                if (unwrappedType.isPrimitive()) {
                    if (!returnType.isPrimitive()) {
                        // Expecting primitive but returnType is Object (e.g map property), rare but
                        // possible, needs unbox if we had it, but sinking properties are usually
                        // strictly typed in VarDecl.
                        // If it's strictly typed from VarDecl, PropertyResolver has already emitted the
                        // primitive.
                        // Do nothing, assuming PropertyResolver returns the right primitive type for
                        // primitive fields.
                    }
                } else if (returnType.isPrimitive()) {
                    ASMUtils.emitBox(mv, returnType);
                }

            } else if (ScriptIR.isSingleVar(arg) && reqType != String.class) {
                // 纯变量引用 → 直传对象（非 String 参数场景）
                String varName = arg.substring(1, arg.length() - 1);
                int slot = ctx.getSlot(varName);
                if (slot >= 0) {
                    IRType varType = ctx.getType(varName);
                    Class<?> unwrappedReq = com.google.common.primitives.Primitives.unwrap(reqType);
                    if (unwrappedReq.isPrimitive()) {
                        // 方法要求原始类型
                        if (varType.isPrimitive()) {
                            // 变量本身是原始类型 → 直接 XLOAD
                            int loadOp;
                            switch (varType.base()) {
                                case INT:
                                case BOOLEAN:
                                    loadOp = Opcodes.ILOAD;
                                    break;
                                case LONG:
                                    loadOp = Opcodes.LLOAD;
                                    break;
                                case DOUBLE:
                                    loadOp = Opcodes.DLOAD;
                                    break;
                                default:
                                    loadOp = Opcodes.ALOAD;
                                    break;
                            }
                            mv.visitVarInsn(loadOp, slot);
                        } else {
                            // 变量是引用类型但方法要原始类型 → ALOAD + 拆箱
                            mv.visitVarInsn(Opcodes.ALOAD, slot);
                            ASMUtils.emitUnbox(mv, IRType.fromClass(unwrappedReq));
                        }
                    } else {
                        // 方法要求引用类型 → 加载并按需装箱
                        ASMUtils.emitLoadBoxed(mv, slot, varType);
                        if (reqType != Object.class) {
                            mv.visitTypeInsn(Opcodes.CHECKCAST,
                                    org.objectweb.asm.Type.getInternalName(reqType));
                        }
                    }
                } else {
                    // 变量未找到，fallback 到字符串
                    mv.visitLdcInsn(arg);
                }
            } else if (ScriptIR.isTemplate(arg)) {
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
    public FlowNode stripProducedVariable(FlowNode node) {
        return node.withoutAttr("store");
    }

    @Override
    public String getConsumedVariable(FlowNode node) {
        ImmutableList<String> args = node.getAttrOrDefault("args", ImmutableList.of());
        String foundVar = null;
        for (String arg : args) {
            if (ScriptIR.isSingleVar(arg)) {
                if (foundVar != null) {
                    // More than one pure variable arg, too complex to sink right now
                    return null;
                }
                foundVar = arg.substring(1, arg.length() - 1);
            }
        }
        return foundVar;
    }

    @Override
    public FlowNode inlineAction(FlowNode node, FlowNode inlineHook) {
        // Find which arg needs replacing
        ImmutableList<String> args = node.getAttrOrDefault("args", ImmutableList.of());
        int targetIndex = -1;
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (ScriptIR.isSingleVar(arg)) {
                targetIndex = i;
                break;
            }
        }

        return node.withAttr("conditionAction", inlineHook).withAttr("_sink_arg_index", targetIndex);
    }

    @Override
    public Iterable<FlowNode> traverseChildren(FlowNode node) {
        FlowNode conditionAction = node.getAttrOrDefault("conditionAction", null);
        if (conditionAction != null) {
            return List.of(conditionAction);
        }
        return List.of();
    }

    @Override
    public List<String> getAllConsumedVariables(FlowNode node) {
        ImmutableList<String> args = node.getAttrOrDefault("args", ImmutableList.of());
        List<String> vars = new java.util.ArrayList<>();
        for (String arg : args) {
            if (ScriptIR.isSingleVar(arg)) {
                vars.add(arg.substring(1, arg.length() - 1));
            } else if (ScriptIR.isTemplate(arg)) {
                for (String part : ScriptIR.parseTemplate(arg)) {
                    if (arg.contains("{" + part + "}")) {
                        vars.add(part);
                    }
                }
            }
        }
        return vars;
    }

    @Override
    public void validateTypes(FlowNode node, CompilationContext ctx) {
        ActionRegistry.ActionDef def = node.getRequiredAttr("def");
        String actionName = node.getRequiredAttr("action");
        ImmutableList<String> args = node.getAttrOrDefault("args", ImmutableList.of());
        com.google.common.reflect.TypeToken<?>[] genericPTypes = def.genericParamTypes();

        for (int i = 0; i < args.size(); i++) {
            int paramIndex = i + 1; // 0 是隐式 Payload/Event
            com.google.common.reflect.TypeToken<?> expectedToken = genericPTypes[paramIndex];
            IRType expectedIR = IRType.fromToken(expectedToken);
            String argStr = args.get(i);

            if (ScriptIR.isSingleVar(argStr)) {
                validateVarArgType(actionName, paramIndex, argStr, expectedIR, ctx);
            } else if (ScriptIR.isTemplate(argStr)) {
                validateTemplateArgType(actionName, paramIndex, argStr, expectedIR);
            } else {
                validateLiteralArgType(actionName, paramIndex, argStr, expectedToken.getRawType());
            }
        }
    }

    private static void validateVarArgType(String action, int paramIndex, String argStr,
            IRType expected, CompilationContext ctx) {
        String varName = argStr.substring(1, argStr.length() - 1);
        if ("payload".equals(varName))
            return;

        IRType actual = ctx.getType(varName);
        if (expected.isAssignableFrom(actual))
            return;

        throw new cn.warriorview.script.core.ScriptCompileException(String.format(
                "Action '%s' expects %s at argument %d, but variable '{%s}' is of type %s.",
                action, expected, paramIndex, varName, actual));
    }

    private static void validateTemplateArgType(String action, int paramIndex, String argStr,
            IRType expected) {
        if (expected == IRType.STRING || expected == IRType.OBJECT)
            return;

        throw new cn.warriorview.script.core.ScriptCompileException(String.format(
                "Action '%s' expects %s at argument %d, but a string template '%s' was provided.",
                action, expected, paramIndex, argStr));
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static void validateLiteralArgType(String action, int paramIndex, String argStr,
            Class<?> expectedJavaType) {
        if (!expectedJavaType.isEnum())
            return;

        try {
            Enum.valueOf((Class<Enum>) expectedJavaType, argStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new cn.warriorview.script.core.ScriptCompileException(String.format(
                    "Invalid enum value '%s' for action '%s' at argument %d. Expected enum type %s",
                    argStr, action, paramIndex, expectedJavaType.getSimpleName()));
        }
    }
}
