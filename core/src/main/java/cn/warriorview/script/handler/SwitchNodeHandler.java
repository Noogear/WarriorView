package cn.warriorview.script.handler;

import cn.warriorview.script.core.CompilationContext;
import cn.warriorview.script.core.ScriptIR;
import cn.warriorview.script.core.ScriptIR.FlowNode;
import cn.warriorview.script.core.ScriptIR.FlowNodeType;
import cn.warriorview.script.core.ScriptIR.IRType;
import cn.warriorview.script.core.ScriptIR.NodeCapability;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * SWITCH 节点处理器。
 * <p>
 * 枚举类型编译为 {@code ordinal()} + {@code LOOKUPSWITCH}，
 * 其他稀疏值使用 hashCode + {@code LOOKUPSWITCH} + equals 验证。
 */
@SuppressWarnings("null")
public final class SwitchNodeHandler implements ScriptIR.FlowNodeHandler {

    static {
        FlowNodeType.registerHandler(FlowNodeType.SWITCH, SwitchNodeHandler::new);
    }

    public static void init() {
    }

    @Override
    @SuppressWarnings("unchecked")
    public FlowNode parse(Map<String, Object> yaml) {
        String variable = (String) yaml.get("variable");
        Map<String, Object> casesRaw = (Map<String, Object>) yaml.get("cases");

        ImmutableMap.Builder<String, ImmutableList<FlowNode>> cases = ImmutableMap.builder();
        if (casesRaw != null) {
            for (Map.Entry<String, Object> entry : casesRaw.entrySet()) {
                String key = entry.getKey();
                List<Map<String, Object>> actions = (List<Map<String, Object>>) entry.getValue();
                ImmutableList.Builder<FlowNode> actionNodes = ImmutableList.builder();
                for (Map<String, Object> actionYaml : actions) {
                    actionNodes.add(FlowNodeType.ACTION.handler().parse(actionYaml));
                }
                cases.put(key, actionNodes.build());
            }
        }

        return new FlowNode(FlowNodeType.SWITCH, ImmutableMap.of(
                "variable", variable,
                "cases", cases.build()));
    }

    @Override
    public void emit(FlowNode node, MethodVisitor mv, CompilationContext ctx) {
        String variable = node.attr("variable");
        ImmutableMap<String, ImmutableList<FlowNode>> cases = node.attr("cases");
        int slot = ctx.getSlot(variable);
        IRType type = ctx.getType(variable);

        if (type == IRType.ENUM) {
            emitEnumSwitch(mv, slot, cases, ctx);
        } else {
            emitLookupSwitch(mv, slot, cases, ctx);
        }
    }

    private void emitEnumSwitch(MethodVisitor mv, int slot,
            ImmutableMap<String, ImmutableList<FlowNode>> cases,
            CompilationContext ctx) {
        Label defaultLabel = new Label();
        Label endLabel = new Label();

        mv.visitVarInsn(Opcodes.ALOAD, slot);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Enum", "ordinal", "()I", false);

        int[] keys = new int[cases.size()];
        Label[] labels = new Label[cases.size()];
        String[] caseNames = cases.keySet().toArray(new String[0]);

        for (int i = 0; i < caseNames.length; i++) {
            keys[i] = i;
            labels[i] = new Label();
        }

        mv.visitLookupSwitchInsn(defaultLabel, keys, labels);

        for (int i = 0; i < caseNames.length; i++) {
            mv.visitLabel(labels[i]);
            ImmutableList<FlowNode> actions = cases.get(caseNames[i]);
            for (FlowNode action : actions) {
                action.type().handler().emit(action, mv, ctx);
            }
            mv.visitJumpInsn(Opcodes.GOTO, endLabel);
        }

        mv.visitLabel(defaultLabel);
        mv.visitLabel(endLabel);
    }

    private void emitLookupSwitch(MethodVisitor mv, int slot,
            ImmutableMap<String, ImmutableList<FlowNode>> cases,
            CompilationContext ctx) {
        Label defaultLabel = new Label();
        Label endLabel = new Label();

        mv.visitVarInsn(Opcodes.ALOAD, slot);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "hashCode", "()I", false);

        String[] caseNames = cases.keySet().toArray(new String[0]);
        int n = caseNames.length;

        // IntStream 索引排序（替代手写冒泡排序）
        int[] sortedIdx = IntStream.range(0, n)
                .boxed()
                .sorted((a, b) -> Integer.compare(caseNames[a].hashCode(), caseNames[b].hashCode()))
                .mapToInt(Integer::intValue)
                .toArray();

        int[] keys = new int[n];
        Label[] labels = new Label[n];
        for (int i = 0; i < n; i++) {
            keys[i] = caseNames[sortedIdx[i]].hashCode();
            labels[i] = new Label();
        }

        mv.visitLookupSwitchInsn(defaultLabel, keys, labels);

        for (int i = 0; i < n; i++) {
            int idx = sortedIdx[i];
            mv.visitLabel(labels[i]);
            // hashCode 碰撞保护
            mv.visitVarInsn(Opcodes.ALOAD, slot);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "toString",
                    "()Ljava/lang/String;", false);
            mv.visitLdcInsn(caseNames[idx]);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals",
                    "(Ljava/lang/Object;)Z", false);
            Label mismatch = new Label();
            mv.visitJumpInsn(Opcodes.IFEQ, mismatch);

            ImmutableList<FlowNode> actions = cases.get(caseNames[idx]);
            for (FlowNode action : actions) {
                action.type().handler().emit(action, mv, ctx);
            }
            mv.visitJumpInsn(Opcodes.GOTO, endLabel);

            mv.visitLabel(mismatch);
            mv.visitJumpInsn(Opcodes.GOTO, defaultLabel);
        }

        mv.visitLabel(defaultLabel);
        mv.visitLabel(endLabel);
    }

    @Override
    public EnumSet<NodeCapability> capabilities() {
        return EnumSet.of(NodeCapability.HAS_BRANCHES);
    }
}
