package cn.warriorview.script.math;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Math AST Node representation for zero-boxing compilation.
 *
 * <p>
 * {@link VariableNode} 同时支持两种模式：
 * <ul>
 * <li>脚本内置模式（{@code index == -1}）：通过 {@link CompilationContext} 按名称查找本地变量
 * slot</li>
 * <li>独立 API 模式（{@code index >= 0}）：变量绑定到 {@code double[]} 数组的指定下标，零装箱零哈希</li>
 * </ul>
 */
public sealed interface MathNode permits
        MathNode.LiteralNode,
        MathNode.VariableNode,
        MathNode.BinaryNode,
        MathNode.UnaryNode,
        MathNode.FunctionNode {

    record LiteralNode(double value) implements MathNode {
    }

    /**
     * 变量节点。
     *
     * @param name  变量名（脚本内模式使用，index==-1 时有效）
     * @param index 数组下标（独立 API 使用）；{@code -1} 表示使用名称解析
     */
    record VariableNode(String name, int index) implements MathNode {
        /** 脚本内模式：仅按名称解析 */
        public VariableNode(String name) {
            this(name, -1);
        }
    }

    record BinaryNode(MathNode left, MathNode right, Operator op) implements MathNode {
    }

    record UnaryNode(MathNode operand, boolean isNegation) implements MathNode {
    }

    record FunctionNode(MathFunction function, List<MathNode> arguments) implements MathNode {
    }

    // ======================== 静态工具方法 ========================

    /**
     * 收集 AST 中所有变量节点的名称（脚本 IR 路径，{@code index == -1}）。
     * 替代 {@code MathNodeHandler.collectVars} 的重复实现。
     */
    static List<String> collectVarNames(MathNode root) {
        List<String> vars = new ArrayList<>();
        collectVarNamesInto(root, vars);
        return vars;
    }

    private static void collectVarNamesInto(MathNode node, List<String> list) {
        switch (node) {
            case LiteralNode ignored -> {}
            case VariableNode v -> list.add(v.name());
            case UnaryNode u -> collectVarNamesInto(u.operand(), list);
            case BinaryNode b -> {
                collectVarNamesInto(b.left(), list);
                collectVarNamesInto(b.right(), list);
            }
            case FunctionNode f -> {
                for (MathNode arg : f.arguments()) collectVarNamesInto(arg, list);
            }
        }
    }

    /**
     * 统计 AST 中每个变量下标（{@link VariableNode#index()}，需 {@code >= 0}）出现的次数。
     * 替代 {@code MathEngine.countVarUsage} 的重复实现，用于重复变量局部缓存决策。
     */
    static Map<Integer, Integer> countUsages(MathNode root) {
        Map<Integer, Integer> counts = new HashMap<>();
        countUsagesInto(root, counts);
        return counts;
    }

    private static void countUsagesInto(MathNode node, Map<Integer, Integer> counts) {
        switch (node) {
            case LiteralNode ignored -> {}
            case VariableNode v -> {
                if (v.index() >= 0) counts.merge(v.index(), 1, (a, b) -> a + b);
            }
            case UnaryNode u -> countUsagesInto(u.operand(), counts);
            case BinaryNode b -> {
                countUsagesInto(b.left(), counts);
                countUsagesInto(b.right(), counts);
            }
            case FunctionNode f -> {
                for (MathNode arg : f.arguments()) countUsagesInto(arg, counts);
            }
        }
    }
}
