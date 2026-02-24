package cn.warriorview.script.api;

import cn.warriorview.script.core.CompilationPipeline;
import cn.warriorview.script.core.CompilationPipeline.CompiledScript;
import cn.warriorview.script.core.ScriptIR.FlowNode;
import cn.warriorview.script.core.ScriptIR.FlowNodeType;
import cn.warriorview.script.core.ScriptIR.IRType;
import cn.warriorview.script.core.ScriptIR.ScriptUnit;
import cn.warriorview.script.core.ScriptIR.VarDecl;
import cn.warriorview.script.parser.ScriptParser;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.util.function.Consumer;

/**
 * 纯 Java 环境下的脚本无字面量（YAML）构建器。
 * <p>
 * 为开发者提供脱离 YAML 文本、基于链式调用直接生成内部抽象语法树（AST）和执行回调的超高速 API。
 */
public final class ScriptBuilder {

    private final String payloadClass;
    private final Class<?> payloadClazz;
    private final ImmutableList.Builder<VarDecl> vars = ImmutableList.<VarDecl>builder();
    private final ImmutableList.Builder<FlowNode> flow = ImmutableList.<FlowNode>builder();

    private ScriptBuilder(Class<?> payloadClass) {
        this.payloadClass = payloadClass.getName();
        this.payloadClazz = payloadClass;
    }

    /**
     * 创建一个新的脚构建器，并绑定数据载体类。
     *
     * @param payloadClass 该脚本依赖的底层数据源类环境（如 PlayerData.class）
     */
    public static ScriptBuilder on(Class<?> payloadClass) {
        return new ScriptBuilder(payloadClass);
    }

    /**
     * 定义一个允许脚本中操作和提取的底层变量。
     * 
     * @param varName    暴露给脚本内部计算的变量别名（例如 "hp"）
     * @param property   底层类的真实属性取值链（例如 "health" 会映射为 getHealth()）
     * @param returnType 该属性推定的真实返回类型
     */
    public ScriptBuilder defineVar(String varName, String property, IRType returnType) {
        vars.add(new VarDecl(varName, property, returnType));
        return this;
    }

    /**
     * 定义一个允许脚本操作的变量，并自动通过反射推导其返回的 IR 类型。
     * 完美支持套娃（级联）属性查找，例如 "player.inventory.itemInMainHand.amount"。
     *
     * @param varName  暴露给脚本内部计算的变量别名（例如 "数量"）
     * @param property 底层类的真实属性取值链（会映射为对应的连续 getters）
     */
    public ScriptBuilder defineVar(String varName, String property) {
        IRType inferredType = ScriptParser.PropertyResolver.resolveType(payloadClazz, property);
        return defineVar(varName, property, inferredType);
    }

    /**
     * 追加一个判断限制节点（如果此条件不符合，底层的执行器会在该位置停止，类似 Kotlin 的 takeIf）。
     *
     * @param variable 比对的变量
     * @param op       操作符（如 ">", "<", "=="，也支持 "!" 前缀反选）
     * @param value    比对的值
     */
    public ScriptBuilder check(String variable, String op, Object value) {
        double numericValue = (value instanceof Number n) ? n.doubleValue() : 0.0;
        flow.add(new FlowNode(FlowNodeType.CHECK, ImmutableMap.<String, Object>of(
                "variable", variable,
                "op", op,
                "value", value), numericValue, 0));
        return this;
    }

    /**
     * 追加一个要触发的动作节点（Action）。
     *
     * @param actionName 在 ActionRegistry 中已经注册好的 @ScriptAction 的名字（比如
     *                   "sendMessage"）
     * @param args       顺序填入的参数列表（支持 {@code "{变量名}"} 的模板插值法）
     */
    public ScriptBuilder action(String actionName, Object... args) {
        flow.add(new FlowNode(FlowNodeType.ACTION, ImmutableMap.<String, Object>of(
                "action", actionName,
                "args", ImmutableList.copyOf(args))));
        return this;
    }

    /**
     * 追加一个拦截终止节点，这会彻底阻止脚本的向下运作，且有可能会直接取消对应 Bukkit Event（如果配置了相关的宿主支持）。
     */
    public ScriptBuilder interrupt() {
        flow.add(new FlowNode(FlowNodeType.RETURN, ImmutableMap.<String, Object>of()));
        return this;
    }

    /**
     * 追加一个多分支选择节点（Switch）。
     *
     * @param variable 比对的变量
     * @param config   分支流程构造器
     */
    public ScriptBuilder switchBranch(String variable, Consumer<SwitchBuilder> config) {
        SwitchBuilder builder = new SwitchBuilder(payloadClazz);
        config.accept(builder);
        flow.add(new FlowNode(FlowNodeType.SWITCH, ImmutableMap.<String, Object>of(
                "variable", variable,
                "cases", builder.buildCases())));
        return this;
    }

    /**
     * Switch 分支的内部构造器
     */
    public static final class SwitchBuilder {
        private final Class<?> payloadClazz;
        private final ImmutableMap.Builder<String, ImmutableList<FlowNode>> cases = ImmutableMap.builder();

        private SwitchBuilder(Class<?> payloadClazz) {
            this.payloadClazz = payloadClazz;
        }

        /**
         * 构造一个 Case 分支区块（支持 String、Enum 或 Number 自动转字符串底层表示）。
         *
         * @param caseKey       匹配的确切键
         * @param branchBuilder 分支内的脚本流程配置
         */
        public SwitchBuilder caseOf(Object caseKey, Consumer<ScriptBuilder> branchBuilder) {
            ScriptBuilder subBuilder = new ScriptBuilder(payloadClazz);
            branchBuilder.accept(subBuilder);
            cases.put(String.valueOf(caseKey), subBuilder.flow.build());
            return this;
        }

        private ImmutableMap<String, ImmutableList<FlowNode>> buildCases() {
            return cases.build();
        }
    }

    /**
     * 从一段 YAML 文本中直接解析流程控制节点阵列并追加至引擎后台。
     * 可以与其他手写的 builder API (如 check, action) 混编使用！
     * 
     * @param yamlContent YAML 文本（例："- check: ... \n - action: ..."）
     */
    public ScriptBuilder flowFromYaml(String yamlContent) {
        ScriptParser parser = new ScriptParser();
        ImmutableList<FlowNode> parsedNodes = parser.parseFlow(yamlContent);
        flow.addAll(parsedNodes);
        return this;
    }

    /**
     * 完成配置，直接将这套规则送去底层的 AOT 引擎！
     * <p>
     * 
     * @return 一个可以随意反复被调用且运行速度等同等同于原生硬编码 Java 代码的回调函数
     */
    public Consumer<Object> compile() {
        ScriptUnit scriptUnit = new ScriptUnit(payloadClass, 0, vars.build(), flow.build());
        CompilationPipeline pipeline = new CompilationPipeline();
        CompiledScript compiled = pipeline.compile(scriptUnit);
        return (Consumer<Object>) compiled.newHandler();
    }
}
