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

import java.util.ArrayList;
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
public final class SwitchNodeHandler
        implements ScriptIR.FlowNodeHandler, ScriptIR.NodeTraverser, ScriptIR.VariableConsumer,
        ScriptIR.BranchReorderer {

    static {
        FlowNodeType.registerHandler(FlowNodeType.SWITCH, SwitchNodeHandler::new);
    }

    public static void init() {
    }

    @Override
    @SuppressWarnings("unchecked")
    public FlowNode parse(Map<String, Object> yaml) {
        String variable = (String) yaml.get("variable");
        if (variable == null) {
            throw new cn.warriorview.script.core.ScriptCompileException(
                    "SWITCH node requires a 'variable' field.");
        }

        Map<String, Object> casesRaw = (Map<String, Object>) yaml.get("cases");
        if (casesRaw == null || casesRaw.isEmpty()) {
            throw new cn.warriorview.script.core.ScriptCompileException(
                    "SWITCH node requires at least one case in 'cases'.");
        }

        ImmutableMap.Builder<String, ImmutableList<FlowNode>> cases = ImmutableMap.builder();
        for (Map.Entry<String, Object> entry : casesRaw.entrySet()) {
            String key = entry.getKey();
            List<Map<String, Object>> actions = (List<Map<String, Object>>) entry.getValue();
            ImmutableList.Builder<FlowNode> actionNodes = ImmutableList.builder();
            for (Map<String, Object> actionYaml : actions) {
                actionNodes.add(FlowNodeType.ACTION.handler().parse(actionYaml));
            }
            cases.put(key, actionNodes.build());
        }

        return new FlowNode(FlowNodeType.SWITCH, ImmutableMap.of(
                "variable", variable,
                "cases", cases.build()));
    }

    @Override
    public void emit(FlowNode node, MethodVisitor mv, CompilationContext ctx) {
        ImmutableMap<String, ImmutableList<FlowNode>> cases = node.getRequiredAttr("cases");

        String variable = null;
        int slot;
        IRType type;
        FlowNode conditionAction = node.getAttrOrDefault("conditionAction", null);

        if (conditionAction != null) {
            String sinkingProp = conditionAction.getAttrOrDefault("_sinking_property", null);
            if (sinkingProp != null) {
                // Property Sinking 闭包：即时解析并提取
                slot = ctx.nextSlot();
                mv.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 1);
                java.util.List<cn.warriorview.script.parser.accessor.PropertyAccessor> accessors = cn.warriorview.script.parser.ScriptParser.PropertyResolver
                        .resolveAccessors(
                                com.google.common.reflect.TypeToken.of(ctx.payloadClass()), sinkingProp);
                for (cn.warriorview.script.parser.accessor.PropertyAccessor acr : accessors) {
                    acr.emitLoad(mv);
                }
                type = conditionAction.getRequiredAttr("returnType");
                int storeOp = cn.warriorview.script.codegen.ASMUtils.storeOpcode(type);
                mv.visitVarInsn(storeOp, slot);
            } else {
                // 普通 Action 压入栈（注意：对于 Switch，由于多分支操作需要反复读取变量，所以依然要存临时 Slot）
                slot = ctx.nextSlot();
                conditionAction.type().handler().emit(conditionAction, mv, ctx);
                type = conditionAction.getRequiredAttr("returnType");
                int storeOp = cn.warriorview.script.codegen.ASMUtils.storeOpcode(type);
                mv.visitVarInsn(storeOp, slot);
            }
        } else {
            variable = node.getRequiredAttr("variable");
            slot = ctx.getSlot(variable);
            type = ctx.getType(variable);
        }

        // 如果仍保留外部传入的实验性策略则运用，否则在运行时进行即时降级策略演算
        String strategy = node.getAttrOrDefault("_switchStrategy", "AUTO");

        if ("AUTO".equals(strategy) || "CASCADE".equals(strategy)) {
            if (type == IRType.ENUM) {
                // Enum 统一使用基于 hashCode 的哈希查表
                strategy = "LOOKUP_STRING";

                // AOT: 检查 case key 是否符合 Java 标识符规则（枚举常量命名约定）
                for (String key : cases.keySet()) {
                    if (!isValidEnumName(key)) {
                        java.util.logging.Logger.getLogger("WarriorView-Script").warning(
                                String.format("SWITCH case key '%s' does not look like a valid enum constant name.",
                                        key));
                    }
                }
            } else if (type == IRType.INT) {
                int min = Integer.MAX_VALUE;
                int max = Integer.MIN_VALUE;
                boolean allInts = true;

                for (String key : cases.keySet()) {
                    try {
                        int v = Integer.parseInt(key);
                        min = Math.min(min, v);
                        max = Math.max(max, v);
                    } catch (NumberFormatException e) {
                        allInts = false;
                        break;
                    }
                }

                if (allInts && cases.size() > 0) {
                    long span = (long) max - min + 1;
                    if (span <= cases.size() * 2.5 && span <= 1000) {
                        strategy = "TABLE_INT";
                    } else {
                        strategy = "LOOKUP_INT";
                    }
                } else {
                    strategy = "CASCADE";
                }
            } else if (type == IRType.STRING) {
                strategy = "LOOKUP_STRING";
            } else {
                strategy = "CASCADE";
            }
        }

        switch (strategy) {
            case "TABLE_INT":
                emitTableIntSwitch(mv, slot, cases, ctx);
                break;
            case "LOOKUP_INT":
            case "LOOKUP_STRING":
                emitLookupSwitch(mv, slot, cases, ctx);
                break;
            case "CASCADE":
            default:
                emitCascadeIfElseSwitch(mv, slot, type, cases, ctx);
                break;
        }
    }

    private void emitLookupSwitch(MethodVisitor mv, int slot,
            ImmutableMap<String, ImmutableList<FlowNode>> cases,
            CompilationContext ctx) {
        Label defaultLabel = new Label();
        Label endLabel = new Label();

        mv.visitVarInsn(Opcodes.ALOAD, slot);
        cn.warriorview.script.codegen.ASMUtils.emitHashCode(mv);

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
            cn.warriorview.script.codegen.ASMUtils.emitEquals(mv);
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
                cn.warriorview.script.codegen.ASMUtils.emitIntConst(mv, expected);
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
                cn.warriorview.script.codegen.ASMUtils.emitDoubleConst(mv, expected);
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
                cn.warriorview.script.codegen.ASMUtils.emitEquals(mv);
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

    @Override
    public Iterable<FlowNode> traverseChildren(FlowNode node) {
        ArrayList<FlowNode> children = new ArrayList<>();
        FlowNode conditionAction = node.getAttrOrDefault("conditionAction", null);
        if (conditionAction != null) {
            children.add(conditionAction);
        }
        ImmutableMap<String, ImmutableList<FlowNode>> cases = node.getAttrOrDefault("cases", null);
        if (cases != null) {
            for (ImmutableList<FlowNode> actionNodes : cases.values()) {
                children.addAll(actionNodes);
            }
        }
        return children;
    }

    @Override
    public FlowNode reorderBranches(FlowNode node, CompilationContext ctx) {
        String variable = node.getAttrOrDefault("variable", null);
        ImmutableMap<String, ImmutableList<FlowNode>> cases = node.getAttrOrDefault("cases", null);
        double[] weights = ctx.getBranchWeights(variable);

        if (weights != null && weights.length == cases.size()) {
            List<String> keys = new ArrayList<>(cases.keySet());
            java.util.Map<String, Integer> indexMap = new java.util.HashMap<>(keys.size());
            for (int i = 0; i < keys.size(); i++) {
                indexMap.put(keys.get(i), i);
            }

            keys.sort(java.util.Comparator.comparingDouble(k -> -weights[indexMap.get(k)]));

            ImmutableMap.Builder<String, ImmutableList<FlowNode>> sorted = ImmutableMap.builder();
            for (String key : keys) {
                sorted.put(key, cases.get(key));
            }
            return node.withAttr("cases", sorted.build());
        }
        return node;
    }

    /**
     * 检查 case key 是否像合法的枚举常量名（Java 标识符规则）。
     */
    private static boolean isValidEnumName(String name) {
        if (name == null || name.isEmpty())
            return false;
        if (!Character.isJavaIdentifierStart(name.charAt(0)))
            return false;
        for (int i = 1; i < name.length(); i++) {
            if (!Character.isJavaIdentifierPart(name.charAt(i)))
                return false;
        }
        return true;
    }
}
