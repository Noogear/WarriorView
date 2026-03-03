package cn.warriorview.script.handler;

import cn.warriorview.script.core.CompilationContext;
import cn.warriorview.script.core.ScriptIR;
import cn.warriorview.script.core.ScriptIR.FlowNode;
import cn.warriorview.script.core.ScriptIR.FlowNodeType;
import cn.warriorview.script.core.ScriptIR.IRType;
import cn.warriorview.script.core.ScriptIR.NodeCapability;
import cn.warriorview.script.math.MathNode;
import cn.warriorview.script.math.MathNodeEmitter;
import cn.warriorview.script.math.MathParser;
import com.google.common.collect.ImmutableMap;
import org.objectweb.asm.MethodVisitor;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;

public class MathNodeHandler implements ScriptIR.FlowNodeHandler, ScriptIR.VariableProducer, ScriptIR.VariableConsumer {

    static {
        FlowNodeType.registerHandler(FlowNodeType.MATH, MathNodeHandler::new);
    }

    public static void init() {
    }

    @Override
    public FlowNode parse(Map<String, Object> yaml) {
        String store = (String) yaml.get("store");
        String expr = (String) yaml.get("expr");

        if (store == null || expr == null) {
            throw new cn.warriorview.script.core.ScriptCompileException(
                    "MATH node requires 'store' and 'expr' fields.");
        }

        MathNode root = MathParser.parse(expr);

        ImmutableMap<String, Object> attrs = ImmutableMap.<String, Object>builder()
                .put("store", store)
                .put("expr", expr)
                .put("mathNode", root)
                .build();

        return new FlowNode(FlowNodeType.valueOf("MATH"), attrs);
    }

    @Override
    public void emit(FlowNode node, MethodVisitor mv, CompilationContext ctx) {
        MathNode root = node.<MathNode>getRequiredAttr("mathNode");

        // Let the BytecodeCompiler handle the pure emission onto the operand stack
        emitMathNode(root, mv, ctx);

        // If not fused, we store to local variable array
        String storeVar = node.getAttrOrDefault("store", null);
        if (storeVar != null) {
            int slot = ctx.getSlot(storeVar);
            mv.visitVarInsn(org.objectweb.asm.Opcodes.DSTORE, slot);
        } else {
            // Fused path (stripProducedVariable was called), leaving double on stack
            // Do nothing
        }
    }

    /**
     * 将 {@link MathNode} AST 发射为 JVM 字节码。
     * 委托给 {@link MathNodeEmitter}（统一实现，含幂整数特化）。
     */
    public static void emitMathNode(MathNode node, MethodVisitor mv, CompilationContext ctx) {
        MathNodeEmitter.emitWithContext(node, mv, ctx);
    }

    @Override
    public EnumSet<NodeCapability> capabilities() {
        return EnumSet.of(NodeCapability.SIDE_EFFECT); // Has side effect of storing to local var
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
    public Object getProducedConstantValue(FlowNode node) {
        MathNode root = node.<MathNode>getRequiredAttr("mathNode");
        return root instanceof MathNode.LiteralNode lit ? lit.value() : null;
    }

    @Override
    public List<String> getAllConsumedVariables(FlowNode node) {
        MathNode root = node.<MathNode>getRequiredAttr("mathNode");
        return MathNode.collectVarNames(root);
    }

}

