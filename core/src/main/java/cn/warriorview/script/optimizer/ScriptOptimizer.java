package cn.warriorview.script.optimizer;

import cn.warriorview.script.core.CompilationContext;
import cn.warriorview.script.core.CompilationContext.ConstantDef;
import cn.warriorview.script.core.ScriptIR;
import cn.warriorview.script.core.ScriptIR.FlowNode;
import cn.warriorview.script.core.ScriptIR.FlowNodeType;
import cn.warriorview.script.core.ScriptIR.NodeCapability;
import cn.warriorview.script.core.ScriptIR.ScriptUnit;
import com.google.common.collect.HashMultiset;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Multiset;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 脚本优化器，合并所有优化 Pass 为单文件。
 * <p>
 * 优化按序执行：常量折叠 → 死代码消除 → 类型特化 → 空检查消除 → 值域传播 → Switch优化 → 分支重排 → 变量缓存
 * <p>
 * 使用 {@link FlowNode#flags} 位掩码替代 attrs Map 存储优化标记（零装箱）。
 */
@SuppressWarnings("null")
public final class ScriptOptimizer {

    public ScriptUnit optimize(ScriptUnit unit, CompilationContext ctx) {
        unit = constantFolding(unit, ctx);
        unit = valueRangePropagation(unit, ctx);
        unit = deadCodeElimination(unit, ctx);

        unit = branchReordering(unit, ctx);
        unit = variableInlining(unit, ctx); // ★ 内联剔除独立声明的 Action 且单次使用的 store
        unit = variableCaching(unit, ctx);

        // 分析 Pass（结果存入 ctx，供 BytecodeCompiler 使用）
        constantHoisting(unit, ctx);
        liveVarAnalysis(unit, ctx);
        return unit;
    }

    // ======================== 值域记录 ========================

    /**
     * 编译期已知的变量值域约束。
     * <p>
     * 只读保证下，一个 check 通过后其约束在整个 accept() 内有效。
     */
    public record ValueRange(double min, double max, Object exactValue, boolean nonNull) {

        public static final ValueRange UNCONSTRAINED = new ValueRange(
                Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, null, false);

        public ValueRange withMin(double newMin) {
            return new ValueRange(Math.max(min, newMin), max, exactValue, nonNull);
        }

        public ValueRange withMax(double newMax) {
            return new ValueRange(min, Math.min(max, newMax), exactValue, nonNull);
        }

        public ValueRange withExact(Object val) {
            double d = val instanceof Number n ? n.doubleValue() : 0;
            return new ValueRange(d, d, val, true);
        }

        public ValueRange withNonNull() {
            return new ValueRange(min, max, exactValue, true);
        }

        /**
         * 判断给定操作是否在当前约束下恒真/恒假。
         *
         * @return Boolean.TRUE=恒真, Boolean.FALSE=恒假, null=不确定
         */
        public Boolean canFold(String op, double cmpValue) {
            return switch (op) {
                case ">" -> min > cmpValue ? Boolean.TRUE : max <= cmpValue ? Boolean.FALSE : null;
                case ">=" -> min >= cmpValue ? Boolean.TRUE : max < cmpValue ? Boolean.FALSE : null;
                case "<" -> max < cmpValue ? Boolean.TRUE : min >= cmpValue ? Boolean.FALSE : null;
                case "<=" -> max <= cmpValue ? Boolean.TRUE : min > cmpValue ? Boolean.FALSE : null;
                case "==" -> {
                    if (exactValue != null) {
                        yield exactValue.equals(cmpValue) || (exactValue instanceof Number n
                                && n.doubleValue() == cmpValue)
                                        ? Boolean.TRUE
                                        : Boolean.FALSE;
                    }
                    yield min == max && min == cmpValue ? Boolean.TRUE : null;
                }
                default -> null;
            };
        }

        /** 用 String exactValue 判断互斥（枚举/字符串 ==） */
        public Boolean canFoldExact(String op, Object cmpValue) {
            if ("==".equals(op) && exactValue != null) {
                return exactValue.equals(cmpValue) ? Boolean.TRUE : Boolean.FALSE;
            }
            return null;
        }
    }

    // ======================== 1. 常量折叠 ========================

    private ScriptUnit constantFolding(ScriptUnit unit, CompilationContext ctx) {
        ImmutableList.Builder<FlowNode> optimized = ImmutableList.builder();
        for (FlowNode node : unit.flow()) {
            if (node.type().handler() instanceof ScriptIR.ConstantFolder folder) {
                Boolean result = folder.evaluateFold(node, ctx);
                if (result == null) {
                    optimized.add(node);
                } else if (result) {
                    optimized.add(node.withFlag(FlowNode.FLAG_FOLDED));
                } else {
                    optimized.add(new FlowNode(FlowNodeType.RETURN, ImmutableMap.of())
                            .withFlag(FlowNode.FLAG_DEAD_AFTER | FlowNode.FLAG_OPTIMIZER_INJECTED));
                    break;
                }
            } else {
                optimized.add(node);
            }
        }
        return unit.withFlow(optimized.build());
    }

    // ======================== 2. 死代码消除 ========================

    private ScriptUnit deadCodeElimination(ScriptUnit unit, CompilationContext ctx) {
        ImmutableList.Builder<FlowNode> optimized = ImmutableList.builder();
        for (FlowNode node : unit.flow()) {
            if (node.hasFlag(FlowNode.FLAG_FOLDED))
                continue;
            optimized.add(node);
            if (node.type().handler().capabilities().contains(NodeCapability.TERMINATES_FLOW)) {
                break;
            }
        }
        return unit.withFlow(optimized.build());
    }

    // ======================== 5. 值域传播（只读保证） ========================

    /**
     * 值域传播优化 Pass。
     * <p>
     * 只读保证下，每个 check 通过后更新变量值域约束。
     * 后续 check 若在约束下恒真/恒假，直接折叠。
     * <p>
     * 复用 {@link #evaluateBaseOp} 的比较逻辑和 {@link FlowNode#flags} 标记机制。
     */
    private ScriptUnit valueRangePropagation(ScriptUnit unit, CompilationContext ctx) {
        Map<String, ValueRange> ranges = new HashMap<>();
        ImmutableList.Builder<FlowNode> optimized = ImmutableList.builder();

        for (FlowNode node : unit.flow()) {
            if (node.type().handler() instanceof ScriptIR.RangePropagator propagator) {
                String var = propagator.getConstrainedVariable(node);
                if (var != null) {
                    ValueRange range = ranges.getOrDefault(var, ValueRange.UNCONSTRAINED);

                    // 尝试用现有约束折叠
                    Boolean foldResult = propagator.tryFoldWithRange(node, range);
                    if (foldResult != null) {
                        if (foldResult) {
                            optimized.add(node.withFlag(FlowNode.FLAG_FOLDED));
                            continue;
                        } else {
                            optimized.add(new FlowNode(FlowNodeType.RETURN, ImmutableMap.of())
                                    .withFlag(FlowNode.FLAG_DEAD_AFTER | FlowNode.FLAG_OPTIMIZER_INJECTED));
                            break;
                        }
                    }

                    // 未折叠 → 更新约束
                    ranges.put(var, propagator.updateRange(node, range));
                }
            }
            optimized.add(node);
        }
        return unit.withFlow(optimized.build());
    }

    // ======================== 7. 分支权重重排 ========================

    private ScriptUnit branchReordering(ScriptUnit unit, CompilationContext ctx) {
        ImmutableList.Builder<FlowNode> optimized = ImmutableList.builder();
        for (FlowNode node : unit.flow()) {
            if (node.type().handler() instanceof ScriptIR.BranchReorderer reorderer) {
                optimized.add(reorderer.reorderBranches(node, ctx));
            } else {
                optimized.add(node);
            }
        }
        return unit.withFlow(optimized.build());
    }

    // ======================== 8. 变量缓存 ========================

    private ScriptUnit variableCaching(ScriptUnit unit, CompilationContext ctx) {
        Multiset<String> usageCount = HashMultiset.create();
        for (FlowNode node : unit.flow()) {
            if (node.type().handler() instanceof ScriptIR.VariableConsumer consumer) {
                String variable = consumer.getConsumedVariable(node);
                if (variable != null) {
                    usageCount.add(variable);
                }
            }
        }

        Set<String> cachedVars = new HashSet<>();
        for (Multiset.Entry<String> entry : usageCount.entrySet()) {
            if (entry.getCount() >= 2) {
                cachedVars.add(entry.getElement());
            }
        }

        if (cachedVars.isEmpty())
            return unit;

        ImmutableList.Builder<FlowNode> optimized = ImmutableList.builder();
        for (FlowNode node : unit.flow()) {
            if (node.type().handler() instanceof ScriptIR.VariableConsumer consumer) {
                String variable = consumer.getConsumedVariable(node);
                if (variable != null && cachedVars.contains(variable)) {
                    optimized.add(node.withFlag(FlowNode.FLAG_CACHED));
                    continue;
                }
            }
            optimized.add(node);
        }
        return unit.withFlow(optimized.build());
    }

    // ======================== 9. 常量提升分析 ========================

    /**
     * 扫描 flow 节点收集需提升为 static final 的常量。
     * <p>
     * 结果存入 {@link CompilationContext#setHoistedConstants}，
     * 由 BytecodeCompiler 生成 {@code <clinit>} 字段。
     */
    private ScriptUnit constantHoisting(ScriptUnit unit, CompilationContext ctx) {
        ArrayList<ConstantDef> defs = new ArrayList<>();
        ImmutableList.Builder<FlowNode> optimized = ImmutableList.builder();
        int[] counter = { 0 };

        for (FlowNode node : unit.flow()) {
            optimized.add(hoistNode(node, defs, counter));
        }
        ctx.setHoistedConstants(ImmutableList.copyOf(defs));
        return unit.withFlow(optimized.build());
    }

    private FlowNode hoistNode(FlowNode node, List<ConstantDef> defs, int[] counter) {
        if (node.type().handler() instanceof ScriptIR.ConstantHoister hoister) {
            node = hoister.hoistConstants(node, defs, counter);
        }

        if (node.type().handler() instanceof ScriptIR.NodeMutator mutator) {
            node = mutator.mapChildren(node, child -> hoistNode(child, defs, counter));
        } else if (node.type().handler() instanceof ScriptIR.NodeTraverser traverser) {
            for (FlowNode child : traverser.traverseChildren(node)) {
                hoistNode(child, defs, counter);
            }
        }
        return node;
    }

    // ======================== 10. 活跃变量分析 ========================

    /**
     * 扫描 flow 节点引用的变量集合。
     * <p>
     * 结果存入 {@link CompilationContext#setLiveVars}，
     * 由 BytecodeCompiler 跳过死变量的提取。
     * <p>
     * 复用 {@link HashMultiset} 统计模式。
     */
    private void liveVarAnalysis(ScriptUnit unit, CompilationContext ctx) {
        Multiset<String> refs = HashMultiset.create();
        for (FlowNode node : unit.flow()) {
            collectLiveVars(node, refs);
        }
        ctx.setLiveVars(refs.elementSet());
    }

    private void collectLiveVars(FlowNode node, Multiset<String> refs) {
        if (node.type().handler() instanceof ScriptIR.VariableConsumer consumer) {
            String var = consumer.getConsumedVariable(node);
            if (var != null) {
                refs.add(var);
            }
        }

        if (node.type().handler() instanceof ScriptIR.NodeTraverser traverser) {
            for (FlowNode child : traverser.traverseChildren(node)) {
                collectLiveVars(child, refs);
            }
        }
    }

    // ======================== 11. 局部变量内联融合 ========================

    /**
     * 指令下沉与窥孔内联优化 (Variable Sinking & Inlining)
     * <p>
     * 1. ActionInlining: 发现独立执行的 ACTION 及其 store，若被下文紧随其后的消费者单次访问，
     * 则摘除包装为闭包供下游消费栈顶处理。
     * 2. PropertySinking: 对于 {@code variables} 环境快照区块的属性声明，若全局唯有 1 处使用，
     * 则踢出预提取 (CSE) 名单，转化为仅在判定点就地发射的虚拟获取闭包。
     */
    private ScriptUnit variableInlining(ScriptUnit unit, CompilationContext ctx) {
        ImmutableList<FlowNode> oldFlow = unit.flow();
        if (oldFlow.size() < 2) {
            return unit;
        }

        // 1. 全域使用次数分析 (含预声明的 vars 与中间态)
        Multiset<String> refs = HashMultiset.create();
        for (FlowNode node : oldFlow) {
            if (node.type().handler() instanceof ScriptIR.VariableConsumer consumer) {
                String var = consumer.getConsumedVariable(node);
                if (var != null) {
                    refs.add(var);
                }
            }
        }

        // 2. 环境快照下沉提取池 (Property Sinking Pool)
        Map<String, cn.warriorview.script.core.ScriptIR.VarDecl> sinkingVars = new HashMap<>();
        ImmutableList.Builder<cn.warriorview.script.core.ScriptIR.VarDecl> optimizedVars = ImmutableList.builder();

        for (cn.warriorview.script.core.ScriptIR.VarDecl v : unit.vars()) {
            if (refs.count(v.name()) == 1) {
                // 单次引用，从 CSE 数组中踢出，转入待下放池
                sinkingVars.put(v.name(), v);
            } else {
                optimizedVars.add(v);
            }
        }

        // 3. 窥孔扫描：寻找 [单测存入 -> 相邻立即消耗] 的 AST 连对，并处理安全下沉
        List<FlowNode> optimized = new ArrayList<>(oldFlow.size());
        for (int i = 0; i < oldFlow.size(); i++) {
            FlowNode current = oldFlow.get(i);

            // ==== 【阶段 A】 侦测并吞食 Action Inlining ====
            if (current.type().handler() instanceof ScriptIR.VariableProducer producer) {
                String storeTarget = producer.getProducedVariable(current);
                if (storeTarget != null && refs.count(storeTarget) == 1) {
                    if (i + 1 < oldFlow.size()) {
                        FlowNode next = oldFlow.get(i + 1);
                        if (next.type().handler() instanceof ScriptIR.VariableConsumer consumer) {
                            String nextVar = consumer.getConsumedVariable(next);
                            if (storeTarget.equals(nextVar)) {
                                FlowNode peelAction = current.withoutAttr("store");
                                FlowNode modifiedCheck = consumer.inlineAction(next, peelAction);
                                optimized.add(modifiedCheck);
                                i++; // 跳过消费节点
                                continue;
                            }
                        }
                    }
                }
            }

            // ==== 【阶段 B】 处理当前节点的按需下沉消费 (Property Sinking) ====
            if (current.type().handler() instanceof ScriptIR.VariableConsumer consumer) {
                String reqVar = consumer.getConsumedVariable(current);
                if (reqVar != null && sinkingVars.containsKey(reqVar)) {
                    cn.warriorview.script.core.ScriptIR.VarDecl decl = sinkingVars.get(reqVar);

                    // 构建一个匿名 ActionNode 作为模拟获取器，它不会经过标准的 emit 执行分发
                    // 它只会被消费节点 (如 Check) 特判并通过附带的 Accessor 执行内联出栈
                    ScriptIR.VariableProducer dummyProducer = (ScriptIR.VariableProducer) FlowNodeType.ACTION.handler();
                    FlowNode virtualHook = dummyProducer.createVirtualProducer(decl);

                    FlowNode modifiedTarget = consumer.inlineAction(current, virtualHook);

                    optimized.add(modifiedTarget);
                    continue;
                }
            }

            // 常规落空兜底
            optimized.add(current);
        }

        return unit.withFlow(ImmutableList.copyOf(optimized)).withVars(optimizedVars.build());
    }
}
