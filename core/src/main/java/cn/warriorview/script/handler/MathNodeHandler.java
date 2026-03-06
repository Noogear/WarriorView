package cn.warriorview.script.handler;

import cn.warriorview.script.core.ParseContext;
import cn.warriorview.script.core.CompilationContext;
import cn.warriorview.script.core.ScriptIR;
import cn.warriorview.script.core.ScriptIR.FlowNode;
import cn.warriorview.script.core.ScriptIR.FlowNodeType;
import cn.warriorview.script.core.ScriptIR.NodeCapability;
import cn.warriorview.script.math.MathEngine;
import cn.warriorview.script.math.MathNode;
import cn.warriorview.script.math.MathNodeEmitter;
import cn.warriorview.script.math.MathParser;
import com.google.common.collect.ImmutableMap;
import org.objectweb.asm.MethodVisitor;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * MATH 节点处理器。
 * <p>
 * 将数学表达式解析为 {@link MathNode} AST 并在 script 字节码中<b>内联发射</b>。
 * 这与 {@link MathEngine#compile} 的「每表达式 → 独立类」策略不同：
 * script 路径将所有 MATH 节点直接编译进同一个 Hidden Class 的方法体中，
 * 天然享有「单类多表达式」的优势（零额外类加载、共享常量池、JIT 跨表达式内联）。
 *
 * <h3>批量预编译（Standalone 场景）</h3>
 * 若需要在 script 之外独立评估脚本中所有 MATH 表达式，
 * 可调用 {@link #batchPrecompile} 将它们通过 {@link MathEngine#compileBatch}
 * 合并到单个 JVM 类中，避免 N 次 defineClass 开销。
 */
public class MathNodeHandler implements ScriptIR.FlowNodeHandler, ScriptIR.VariableProducer, ScriptIR.VariableConsumer {

    static {
        FlowNodeType.registerHandler(FlowNodeType.MATH, MathNodeHandler::new);
    }

    public static void init() {
    }

    @Override
    public FlowNode parse(ParseContext ctx) {
        String store = ctx.get("store");
        String expr = ctx.get("expr");

        if (store == null || expr == null) {
            throw ctx.error("MATH node requires 'store' and 'expr' fields.");
        }

        MathNode root = MathParser.parse(expr);

        ImmutableMap<String, Object> nodeAttrs = ImmutableMap.<String, Object>builder()
                .put("store", store)
                .put("expr", expr)
                .put("mathNode", root)
                .build();

        return new FlowNode(FlowNodeType.valueOf("MATH"), nodeAttrs);
    }

    @Override
    public void emit(FlowNode node, MethodVisitor mv, CompilationContext ctx) {
        MathNode root = node.<MathNode>getRequiredAttr("mathNode");

        // 直接将 MathNode AST 内联发射为 JVM 字节码（零间接调用，最优路径）
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
        MathNodeEmitter.emit(node, mv, ctx.toVariableEmitter());
    }

    // ======================== 批量预编译（Standalone 场景） ========================

    /**
     * 收集脚本中所有 MATH 节点的表达式，通过 {@link MathEngine#compileBatch}
     * 合并编译到<b>单个 JVM 类</b>中，供脚本外部独立评估使用。
     *
     * <h3>适用场景</h3>
     * <ul>
     *   <li>编辑器预览 / 断点调试需要独立求值各 MATH 表达式</li>
     *   <li>单元测试中批量验证表达式正确性</li>
     *   <li>非 script 上下文需要复用脚本中定义的数学公式</li>
     * </ul>
     *
     * <h3>注意事项</h3>
     * script 编译路径已将 MATH 内联到同一方法体中，性能优于 standalone 批量编译。
     * 此 API 仅为非 script 场景提供便利。
     *
     * @param unit 已解析的脚本单元
     * @return 批量编译结果，{@code null} 表示脚本中无 MATH 节点
     */
    public static MathEngine.BatchResult batchPrecompile(ScriptIR.ScriptUnit unit) {
        // 1. 收集所有 MATH 节点的表达式和变量名
        List<String> expressions = new ArrayList<>();
        Set<String> varNameSet = new LinkedHashSet<>();

        for (FlowNode node : unit.flow()) {
            collectMathExpressions(node, expressions, varNameSet);
        }

        if (expressions.isEmpty()) {
            return null;
        }

        // 2. 合并变量名（保持插入顺序，作为 evaluate 参数索引）
        String[] varNames = varNameSet.toArray(String[]::new);

        // 3. 批量编译：所有表达式 → 单个 JVM 类
        return MathEngine.compileBatch(expressions.toArray(String[]::new), varNames);
    }

    /**
     * 递归收集节点树中所有 MATH 节点的表达式和消费变量。
     */
    private static void collectMathExpressions(FlowNode node, List<String> expressions, Set<String> varNames) {
        if (node.type() == FlowNodeType.MATH) {
            String expr = node.getAttrOrDefault("expr", null);
            if (expr != null) {
                expressions.add(expr);
                // 收集此表达式引用的变量名
                MathNode root = node.getAttrOrDefault("mathNode", null);
                if (root != null) {
                    varNames.addAll(MathNode.collectVarNames(root));
                }
            }
        }

        // 递归子节点
        ScriptIR.FlowNodeHandler handler = node.type().handler();
        if (handler instanceof ScriptIR.NodeTraverser traverser) {
            for (FlowNode child : traverser.traverseChildren(node)) {
                collectMathExpressions(child, expressions, varNames);
            }
        }
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

