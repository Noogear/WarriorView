package cn.warriorview.script.handler;

import cn.warriorview.script.core.CompilationContext;
import cn.warriorview.script.core.ScriptIR;
import cn.warriorview.script.core.ScriptIR.FlowNode;
import cn.warriorview.script.core.ScriptIR.FlowNodeType;
import cn.warriorview.script.core.ScriptIR.NodeCapability;
import com.google.common.collect.ImmutableMap;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.EnumSet;
import java.util.Map;

/**
 * RETURN 节点处理器。
 * <p>
 * 最简处理器，仅发射 {@code RETURN} 指令终止方法执行。
 */
public final class ReturnNodeHandler implements ScriptIR.FlowNodeHandler {

    static {
        FlowNodeType.registerHandler(FlowNodeType.RETURN, ReturnNodeHandler::new);
    }

    public static void init() {
    }

    @Override
    public FlowNode parse(Map<String, Object> yaml) {
        return new FlowNode(FlowNodeType.RETURN, ImmutableMap.of());
    }

    @Override
    public void emit(FlowNode node, MethodVisitor mv, CompilationContext ctx) {
        mv.visitInsn(Opcodes.RETURN);
    }

    @Override
    public EnumSet<NodeCapability> capabilities() {
        return EnumSet.of(NodeCapability.TERMINATES_FLOW);
    }
}
