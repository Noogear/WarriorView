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
        String strategy = node.attr("_switchStrategy");
        if (strategy == null) {
            strategy = "CASCADE";
        }
        int slot = ctx.getSlot(variable);

        switch (strategy) {
            case "TABLE_ENUM":
                emitTableEnumSwitch(mv, slot, variable, cases, ctx);
                break;
            case "TABLE_INT":
                emitTableIntSwitch(mv, slot, cases, ctx);
                break;
            case "LOOKUP_INT":
            case "LOOKUP_STRING":
                emitLookupSwitch(mv, slot, cases, ctx);
                break;
            case "CASCADE":
            default:
                emitCascadeIfElseSwitch(mv, slot, ctx.getType(variable), cases, ctx);
                break;
        }
    }

    private void emitTableEnumSwitch(MethodVisitor mv, int slot, String variable,
            ImmutableMap<String, ImmutableList<FlowNode>> cases,
            CompilationContext ctx) {
        Label defaultLabel = new Label();
        Label endLabel = new Label();

        // 1. 防空指针 (IFNULL 判断)
        mv.visitVarInsn(Opcodes.ALOAD, slot);
        mv.visitJumpInsn(Opcodes.IFNULL, defaultLabel);

        // 2. 取真实变量 ordinal
        mv.visitVarInsn(Opcodes.ALOAD, slot);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Enum", "ordinal", "()I", false);

        // 3. 反射查询所有 Enum 真实实例与序号
        cn.warriorview.script.core.ScriptIR.IRType type = ctx.getType(variable);
        if (type != cn.warriorview.script.core.ScriptIR.IRType.ENUM) {
            throw new IllegalStateException("Variable " + variable + " is treated as TABLE_ENUM but actually " + type);
        }
        Class<?> enumType = ctx.payloadClass(); // Enum 常量将稍后被运行时校验或目前假设已知
        // 为了安全获取类型，暂时尝试使用最暴力的搜寻：
        try {
            java.lang.reflect.Method m = ctx.payloadClass()
                    .getMethod("get" + variable.substring(0, 1).toUpperCase() + variable.substring(1));
            enumType = m.getReturnType();
        } catch (Exception e) {
            // 软降级回退
        }
        Object[] enumConstants = enumType.getEnumConstants();
        if (enumConstants == null) {
            throw new IllegalStateException(
                    "Variable " + variable + " is resolved as ENUM but payload class is not Enum.");
        }

        int maxOrdinal = enumConstants.length;
        Label[] tempLabels = new Label[maxOrdinal];
        for (int i = 0; i < maxOrdinal; i++) {
            tempLabels[i] = defaultLabel; // 默认将所有可能的成员指向 fallback (default)
        }

        // 把 YAML case 定义好的内容塞进具体的 Label
        Label[] caseLabels = new Label[cases.size()];
        String[] caseNames = cases.keySet().toArray(new String[0]);

        for (int i = 0; i < caseNames.length; i++) {
            String name = caseNames[i];
            Label targetLabel = new Label();
            caseLabels[i] = targetLabel;

            // 找出名字在真实枚举类里的对应 ordinal
            for (Object obj : enumConstants) {
                Enum<?> e = (Enum<?>) obj;
                if (e.name().equals(name)) {
                    tempLabels[e.ordinal()] = targetLabel;
                    break;
                }
            }
        }

        // 4. 生成 TABLESWITCH
        // min=0, max=enumLength - 1
        mv.visitTableSwitchInsn(0, maxOrdinal - 1, defaultLabel, tempLabels);

        // 5. 生成 case 内的方法体
        for (int i = 0; i < caseNames.length; i++) {
            mv.visitLabel(caseLabels[i]);
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

    private void emitTableIntSwitch(MethodVisitor mv, int slot,
            ImmutableMap<String, ImmutableList<FlowNode>> cases,
            CompilationContext ctx) {
        Label defaultLabel = new Label();
        Label endLabel = new Label();

        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (String key : cases.keySet()) {
            int v = Integer.parseInt(key);
            if (v < min)
                min = v;
            if (v > max)
                max = v;
        }

        int size = max - min + 1;
        Label[] labels = new Label[size];
        for (int i = 0; i < size; i++) {
            labels[i] = defaultLabel;
        }

        Label[] caseLabels = new Label[cases.size()];
        String[] caseNames = cases.keySet().toArray(new String[0]);
        for (int i = 0; i < caseNames.length; i++) {
            int v = Integer.parseInt(caseNames[i]);
            Label targetLabel = new Label();
            caseLabels[i] = targetLabel;
            labels[v - min] = targetLabel;
        }

        mv.visitVarInsn(Opcodes.ILOAD, slot);
        mv.visitTableSwitchInsn(min, max, defaultLabel, labels);

        for (int i = 0; i < caseNames.length; i++) {
            mv.visitLabel(caseLabels[i]);
            for (FlowNode action : cases.get(caseNames[i])) {
                action.type().handler().emit(action, mv, ctx);
            }
            mv.visitJumpInsn(Opcodes.GOTO, endLabel);
        }

        mv.visitLabel(defaultLabel);
        mv.visitLabel(endLabel);
    }

    private void emitCascadeIfElseSwitch(MethodVisitor mv, int slot, IRType type,
            ImmutableMap<String, ImmutableList<FlowNode>> cases,
            CompilationContext ctx) {
        Label endLabel = new Label();
        Label defaultLabel = new Label(); // 如果没有写 default 则指向 end

        String[] caseNames = cases.keySet().toArray(new String[0]);
        Label[] caseBlockLabels = new Label[cases.size()];
        for (int i = 0; i < cases.size(); i++) {
            caseBlockLabels[i] = new Label();
        }

        for (int i = 0; i < caseNames.length; i++) {
            String key = caseNames[i];
            Label nextCheckLabel = (i == caseNames.length - 1) ? defaultLabel : new Label();

            // 将 YAML 键强制重解析匹配实际目标的常量比对
            if (type == IRType.INT || type == IRType.BOOLEAN) {
                int expected = type == IRType.BOOLEAN ? (Boolean.parseBoolean(key) ? 1 : 0) : Integer.parseInt(key);
                mv.visitVarInsn(Opcodes.ILOAD, slot);
                cn.warriorview.script.codegen.BytecodeCompiler.emitIntConst(mv, expected);
                mv.visitJumpInsn(Opcodes.IF_ICMPNE, nextCheckLabel);
            } else if (type == IRType.LONG) {
                long expected = Long.parseLong(key);
                mv.visitVarInsn(Opcodes.LLOAD, slot);
                mv.visitLdcInsn(expected);
                mv.visitInsn(Opcodes.LCMP);
                mv.visitJumpInsn(Opcodes.IFNE, nextCheckLabel);
            } else if (type == IRType.DOUBLE) {
                double expected = Double.parseDouble(key);
                mv.visitVarInsn(Opcodes.DLOAD, slot);
                cn.warriorview.script.codegen.BytecodeCompiler.emitDoubleConst(mv, expected);
                mv.visitInsn(Opcodes.DCMPG); // 使用统一比对
                mv.visitJumpInsn(Opcodes.IFNE, nextCheckLabel);
            } else {
                // FALLBACK TO OBJECT .equals() 检测
                mv.visitVarInsn(Opcodes.ALOAD, slot);
                mv.visitJumpInsn(Opcodes.IFNULL, nextCheckLabel);
                mv.visitVarInsn(Opcodes.ALOAD, slot);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "toString",
                        "()Ljava/lang/String;", false);
                mv.visitLdcInsn(key);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals",
                        "(Ljava/lang/Object;)Z", false);
                mv.visitJumpInsn(Opcodes.IFEQ, nextCheckLabel);
            }

            // 匹配成功，跳转执行区块
            mv.visitLabel(caseBlockLabels[i]);
            for (FlowNode action : cases.get(key)) {
                action.type().handler().emit(action, mv, ctx);
            }
            mv.visitJumpInsn(Opcodes.GOTO, endLabel);

            // 放置下一个条件的锚点
            if (i < caseNames.length - 1) {
                mv.visitLabel(nextCheckLabel);
            }
        }

        mv.visitLabel(defaultLabel);
        mv.visitLabel(endLabel);
    }

    @Override
    public EnumSet<NodeCapability> capabilities() {
        return EnumSet.of(NodeCapability.HAS_BRANCHES);
    }
}
