package cn.warriorview.script.optimizer;

import cn.warriorview.script.core.CompilationContext;
import cn.warriorview.script.core.CompilationContext.ConstantDef;
import cn.warriorview.script.core.ScriptIR;
import cn.warriorview.script.core.ScriptIR.FlowNode;
import cn.warriorview.script.core.ScriptIR.NodeCapability;
import cn.warriorview.script.core.ScriptIR.ScriptUnit;
import com.google.common.collect.HashMultiset;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Multiset;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 脚本核心优化器。
 * <p>
 * 优化按序执行：常量折叠 → 值域传播 → 死代码消除 → 分支重排 → 变量下沉与内联预估 → 变量缓存推算
 * 此外包含分析型 Pass：常量提取、活跃变量分析。
 * <p>
 * 使用 {@link FlowNode#flags} 位掩码存储优化标记，实现零装箱分配。
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
                    optimized.add(FlowNode.earlyReturn());
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
            // 常量 MATH 产出 → 向后续 CHECK 注入精确值域约束
            if (node.type().handler() instanceof ScriptIR.VariableProducer producer) {
                String var = producer.getProducedVariable(node);
                Object constVal = producer.getProducedConstantValue(node);
                if (var != null && constVal != null) {
                    double d = constVal instanceof Number n ? n.doubleValue() : 0;
                    ranges.put(var, new ValueRange(d, d, constVal, true));
                }
            }
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
                            optimized.add(FlowNode.earlyReturn());
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
            for (String var : consumer.getAllConsumedVariables(node)) {
                if (var != null) {
                    refs.add(var);
                }
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
     * 指令下沉与窥孔内联融合优化 (Variable Sinking & Inlining)
     * <p>
     * 全局扫描利用了抽象接口体系 {@link ScriptIR.VariableProducer} /
     * {@link ScriptIR.VariableConsumer}。
     * <p>
     * 1. 生产者内联 (Producer Inlining): 发现局部声明生产的实体及所输出的变量，
     * 若被紧随其后的消费者单次访问，则剥离自身封装作为环境快照供下游消费闭包栈顶处理。
     * 2. 属性下沉 (Property Sinking): 对于 {@code variables} 环境块内声明的独立提取，若全域唯有 1 处使用，
     * 则剔除预装载 (CSE) 清单，转换为仅在判定点即时通过虚拟生产者 (DummyProducer) 闭包发射动作。
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
            if (v.isPayloadAlias()) {
                // payload 别名不能被下沉（它就是 slot 1，无需提取也无法虚拟化属性链）
                optimizedVars.add(v);
                continue;
            }
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

            // ==== 【阶段 A】 侦测并吞食生产者到消费者的直接内联 (Producer Inlining) ====
            //
            // 改进：允许跨越纯守卫节点 (CHECK / ANY / ALL) 寻找消费者。
            // 被跳过的守卫节点照常输出，仅生产者与消费者合并。
            // 这也是一个安全的子优化：如果中间的 CHECK 提前终止了脚本，
            // 生产者的动作调用会被完全跳过（减少不必要的副作用执行）。
            if (current.type().handler() instanceof ScriptIR.VariableProducer producer) {
                String storeTarget = producer.getProducedVariable(current);
                if (storeTarget != null && refs.count(storeTarget) == 1) {
                    // 前瞻扫描：跳过纯守卫节点，寻找唯一的消费者
                    int consumerIdx = -1;
                    for (int j = i + 1; j < oldFlow.size() && j <= i + 8; j++) {
                        FlowNode candidate = oldFlow.get(j);
                        if (candidate.type().handler() instanceof ScriptIR.VariableConsumer vc) {
                            String cVar = vc.getConsumedVariable(candidate);
                            if (storeTarget.equals(cVar)) {
                                consumerIdx = j;
                                break;
                            }
                        }
                        // 仅允许跳过无副作用的纯守卫节点
                        if (!isPureGuardNode(candidate)) {
                            break;
                        }
                    }

                    if (consumerIdx > 0) {
                        FlowNode peelAction = producer.stripProducedVariable(current);
                        FlowNode consumerNode = oldFlow.get(consumerIdx);
                        ScriptIR.VariableConsumer consumer =
                                (ScriptIR.VariableConsumer) consumerNode.type().handler();
                        FlowNode modifiedConsumer = consumer.inlineAction(consumerNode, peelAction);

                        // 输出中间的守卫节点（保持原始执行顺序）
                        for (int k = i + 1; k < consumerIdx; k++) {
                            optimized.add(oldFlow.get(k));
                        }
                        optimized.add(modifiedConsumer);
                        i = consumerIdx; // 跳过已处理的节点
                        continue;
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
                    FlowNode virtualHook = FlowNode.virtualProducer(decl);

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

    /**
     * 判断节点是否为"纯守卫"——无外部副作用、仅作条件分支控制的节点。
     * <p>
     * 用于 Phase A 前瞻扫描：生产者内联可以安全地跳过这些节点，
     * 使得中间的守卫检查仍然正常执行，而动作调用则延迟到消费者位置。
     */
    private static boolean isPureGuardNode(FlowNode node) {
        return switch (node.type()) {
            case CHECK, ANY, ALL -> true;
            default -> false;
        };
    }
}
