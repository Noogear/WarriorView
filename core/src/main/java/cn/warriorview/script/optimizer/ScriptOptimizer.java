package cn.warriorview.script.optimizer;

import cn.warriorview.script.core.CompilationContext;
import cn.warriorview.script.core.CompilationContext.ConstantDef;
import cn.warriorview.script.core.CompilationContext.ConstantKind;
import cn.warriorview.script.core.ScriptIR.FlowNode;
import cn.warriorview.script.core.ScriptIR.FlowNodeType;
import cn.warriorview.script.core.ScriptIR.NodeCapability;
import cn.warriorview.script.core.ScriptIR.ScriptUnit;
import com.google.common.collect.HashMultiset;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Multiset;

import java.util.ArrayList;
import java.util.Comparator;
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
        unit = deadCodeElimination(unit, ctx);
        unit = nullCheckElimination(unit, ctx);
        unit = valueRangePropagation(unit, ctx);
        unit = branchReordering(unit, ctx);
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
    record ValueRange(double min, double max, Object exactValue, boolean nonNull) {

        static final ValueRange UNCONSTRAINED = new ValueRange(
                Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, null, false);

        ValueRange withMin(double newMin) {
            return new ValueRange(Math.max(min, newMin), max, exactValue, nonNull);
        }

        ValueRange withMax(double newMax) {
            return new ValueRange(min, Math.min(max, newMax), exactValue, nonNull);
        }

        ValueRange withExact(Object val) {
            double d = val instanceof Number n ? n.doubleValue() : 0;
            return new ValueRange(d, d, val, true);
        }

        ValueRange withNonNull() {
            return new ValueRange(min, max, exactValue, true);
        }

        /**
         * 判断给定操作是否在当前约束下恒真/恒假。
         *
         * @return Boolean.TRUE=恒真, Boolean.FALSE=恒假, null=不确定
         */
        Boolean canFold(String op, double cmpValue) {
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
        Boolean canFoldExact(String op, Object cmpValue) {
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
            if (node.type() == FlowNodeType.CHECK && ctx.isConstant(node.getAttrOrDefault("variable", null))) {
                Boolean result = evaluateCheck(node, ctx);
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

    private Boolean evaluateCheck(FlowNode node, CompilationContext ctx) {
        String rawOp = node.getAttrOrDefault("op", null);
        boolean negate = rawOp.startsWith("!");
        String op = negate ? rawOp.substring(1) : rawOp;

        Object varValue = ctx.getConstant(node.getAttrOrDefault("variable", null));

        Boolean result = evaluateBaseOp(op, varValue, node);
        if (result != null && negate)
            result = !result;
        return result;
    }

    private Boolean evaluateBaseOp(String op, Object varValue, FlowNode node) {
        if ("null".equals(op))
            return varValue == null;
        if (varValue == null)
            return null;

        Object cmpValue = node.getAttrOrDefault("value", null);
        if (cmpValue == null && "==".equals(op) && varValue instanceof Boolean b) {
            return b;
        }
        if (cmpValue == null)
            return null;

        if (varValue instanceof Number v && cmpValue instanceof Number c) {
            double vd = v.doubleValue(), cd = c.doubleValue();
            return switch (op) {
                case ">" -> vd > cd;
                case ">=" -> vd >= cd;
                case "<" -> vd < cd;
                case "<=" -> vd <= cd;
                case "==" -> vd == cd;
                default -> null;
            };
        }

        if ("==".equals(op))
            return varValue.equals(cmpValue);
        if ("contains".equals(op) && varValue instanceof String s && cmpValue instanceof String sub) {
            return s.contains(sub);
        }
        return null;
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

    // ======================== 4. 空检查消除 ========================

    private ScriptUnit nullCheckElimination(ScriptUnit unit, CompilationContext ctx) {
        Set<String> provenNonNull = new HashSet<>();
        ImmutableList.Builder<FlowNode> optimized = ImmutableList.builder();

        for (FlowNode node : unit.flow()) {
            if (node.type() == FlowNodeType.CHECK) {
                String variable = node.getAttrOrDefault("variable", null);
                String rawOp = node.getAttrOrDefault("op", null);

                if ("!null".equals(rawOp)) {
                    if (provenNonNull.contains(variable)) {
                        continue;
                    }
                    provenNonNull.add(variable);
                }
            }
            optimized.add(node);
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
            if (node.type() == FlowNodeType.CHECK) {
                String var = node.getAttrOrDefault("variable", null);
                String rawOp = node.getAttrOrDefault("op", null);

                boolean negate = rawOp.startsWith("!");
                String op = negate ? rawOp.substring(1) : rawOp;

                ValueRange range = ranges.getOrDefault(var, ValueRange.UNCONSTRAINED);

                // 尝试用现有约束折叠
                Boolean foldResult = tryFoldWithRange(range, op, node);
                if (foldResult != null) {
                    if (negate)
                        foldResult = !foldResult;
                    if (foldResult) {
                        // 恒真 → 跳过此 check
                        optimized.add(node.withFlag(FlowNode.FLAG_FOLDED));
                        continue;
                    } else {
                        // 恒假 → 截断
                        optimized.add(new FlowNode(FlowNodeType.RETURN, ImmutableMap.of())
                                .withFlag(FlowNode.FLAG_DEAD_AFTER | FlowNode.FLAG_OPTIMIZER_INJECTED));
                        break;
                    }
                }

                // 未折叠 → 更新约束（此 check 通过后的新约束）
                ranges.put(var, updateRange(range, op, node));
            }
            optimized.add(node);
        }
        return unit.withFlow(optimized.build());
    }

    private Boolean tryFoldWithRange(ValueRange range, String op, FlowNode node) {
        // 数值比较折叠
        if (">".equals(op) || ">=".equals(op) || "<".equals(op)
                || "<=".equals(op) || "==".equals(op)) {
            Object value = node.getAttrOrDefault("value", null);
            if (value instanceof Number n) {
                return range.canFold(op, n.doubleValue());
            }
            return range.canFoldExact(op, value);
        }
        // null 折叠
        if ("null".equals(op) && range.nonNull()) {
            return Boolean.FALSE; // 已证非空 → null 检查恒假
        }
        return null;
    }

    private ValueRange updateRange(ValueRange range, String op, FlowNode node) {
        Object value = node.getAttrOrDefault("value", null);
        double d = value instanceof Number n ? n.doubleValue() : 0;

        return switch (op) {
            case ">" -> range.withMin(d + Double.MIN_VALUE);
            case ">=" -> range.withMin(d);
            case "<" -> range.withMax(d - Double.MIN_VALUE);
            case "<=" -> range.withMax(d);
            case "==" -> value != null ? range.withExact(value) : range;
            case "null" -> range; // null check 不改变数值域
            default -> {
                if ("!null".equals(node.getAttrOrDefault("op", null))) {
                    yield range.withNonNull();
                }
                yield range;
            }
        };
    }

    // ======================== 7. 分支权重重排 ========================

    private ScriptUnit branchReordering(ScriptUnit unit, CompilationContext ctx) {
        ImmutableList.Builder<FlowNode> optimized = ImmutableList.builder();
        for (FlowNode node : unit.flow()) {
            if (node.type() == FlowNodeType.SWITCH) {
                String variable = node.getAttrOrDefault("variable", null);
                ImmutableMap<String, ImmutableList<FlowNode>> cases = node.getAttrOrDefault("cases", null);
                double[] weights = ctx.getBranchWeights(variable);

                if (weights != null && weights.length == cases.size()) {
                    List<String> keys = new ArrayList<>(cases.keySet());
                    Map<String, Integer> indexMap = new HashMap<>(keys.size());
                    for (int i = 0; i < keys.size(); i++) {
                        indexMap.put(keys.get(i), i);
                    }

                    keys.sort(Comparator.comparingDouble(k -> -weights[indexMap.get(k)]));

                    ImmutableMap.Builder<String, ImmutableList<FlowNode>> sorted = ImmutableMap.builder();
                    for (String key : keys) {
                        sorted.put(key, cases.get(key));
                    }
                    optimized.add(node.withAttr("cases", sorted.build()));
                } else {
                    optimized.add(node);
                }
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
            String variable = node.getAttrOrDefault("variable", null);
            if (variable != null) {
                usageCount.add(variable);
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
            String variable = node.getAttrOrDefault("variable", null);
            if (variable != null && cachedVars.contains(variable)) {
                optimized.add(node.withFlag(FlowNode.FLAG_CACHED));
            } else {
                optimized.add(node);
            }
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
        int counter = 0;

        for (FlowNode node : unit.flow()) {
            if (node.type() != FlowNodeType.CHECK) {
                optimized.add(node);
                continue;
            }
            String rawOp = node.getAttrOrDefault("op", null);
            String op = rawOp.startsWith("!") ? rawOp.substring(1) : rawOp;
            String fieldName = null;

            if ("matches".equals(op)) {
                String pattern = node.getAttrOrDefault("value", null);
                if (pattern != null) {
                    fieldName = "PATTERN_" + counter++;
                    defs.add(new ConstantDef(fieldName, ConstantKind.PATTERN, pattern));
                }
            } else if ("in".equals(op)) {
                ImmutableList<?> list = node.getAttrOrDefault("valueList", null);
                if (list == null)
                    list = node.getAttrOrDefault("value", null);
                if (list instanceof ImmutableList<?> vals && vals.size() > 3) {
                    fieldName = "SET_" + counter++;
                    defs.add(new ConstantDef(fieldName, ConstantKind.STRING_SET, vals));
                }
            } else if ("between".equals(op)) {
                ImmutableList<?> range = node.getAttrOrDefault("valueList", null);
                if (range == null)
                    range = node.getAttrOrDefault("value", null);
                if (range instanceof ImmutableList<?> vals && vals.size() == 2) {
                    double[] arr = { ((Number) vals.get(0)).doubleValue(),
                            ((Number) vals.get(1)).doubleValue() };
                    fieldName = "RANGE_" + counter++;
                    defs.add(new ConstantDef(fieldName, ConstantKind.DOUBLE_ARRAY, arr));
                }
            }

            if (fieldName != null) {
                optimized.add(node.withAttr("_hoistedField", fieldName));
            } else {
                optimized.add(node);
            }
        }
        ctx.setHoistedConstants(ImmutableList.copyOf(defs));
        return unit.withFlow(optimized.build());
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
            String var = node.getAttrOrDefault("variable", null);
            if (var != null)
                refs.add(var);
        }
        ctx.setLiveVars(refs.elementSet());
    }
}
