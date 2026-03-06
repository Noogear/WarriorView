package cn.warriorview.script.api;

import cn.warriorview.script.action.ActionRegistry;
import cn.warriorview.script.action.ActionRegistry.ActionDef;
import cn.warriorview.script.core.CheckOp;
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
import java.util.function.Function;

/**
 * 纯 Java 环境下的脚本无字面量（YAML）构建器。
 * <p>
 * 为开发者提供脱离 YAML 文本、基于链式调用直接生成内部抽象语法树（AST）和执行回调的超高速 API。
 */
public final class ScriptBuilder {

    private final Class<?> payloadClazz;
    private String scriptId;
    private final ImmutableList.Builder<VarDecl> vars = ImmutableList.<VarDecl>builder();
    private final ImmutableList.Builder<FlowNode> flow = ImmutableList.<FlowNode>builder();
    private ActionRegistry actionRegistry;

    private ScriptBuilder(Class<?> payloadClass) {
        this.payloadClazz = payloadClass;
        this.scriptId = "Builder-" + java.util.UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * 手动指定本脚本的来源标识，用于运行期报错追踪。
     */
    public ScriptBuilder id(String id) {
        this.scriptId = id;
        return this;
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
     * 绑定动作注册表，以支持 {@link #action} 和 {@link #actionStore} 方法中的编译期校验。
     * <p>
     * 若不调用此方法，使用 {@code action()} / {@code actionStore()} 时将抛出
     * {@link IllegalStateException}。
     *
     * @param registry 已扫描注册过动作的 {@link ActionRegistry}
     */
    public ScriptBuilder withActionRegistry(ActionRegistry registry) {
        this.actionRegistry = registry;
        return this;
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

    // ======================== CHECK 节点 ========================

    /**
     * 追加一个判断限制节点（如果此条件不符合，底层的执行器会在该位置停止，类似 Kotlin 的 takeIf）。
     * <p>
     * 操作符：{@code null, ==, >, <, >=, <=, contains, starts_with, ends_with, matches, instanceof}
     * <br>
     * 所有操作符前均可加 {@code !} 前缀取反。
     *
     * @param variable 比对的变量
     * @param op       操作符（如 ">", "<", "=="，也支持 "!" 前缀反选）
     * @param value    比对的值
     */
    public ScriptBuilder check(String variable, String op, Object value) {
        flow.add(buildCheckNode(variable, op, value, null));
        return this;
    }

    /**
     * 追加一个无值判断节点，适用于 {@code null}、{@code instanceof} 等单目操作符。
     *
     * @param variable 比对的变量
     * @param op       操作符（如 "null", "!null"）
     */
    public ScriptBuilder check(String variable, String op) {
        flow.add(buildCheckNode(variable, op, null, null));
        return this;
    }

    /**
     * 追加一个带失败时回调的判断节点。
     * <p>
     * 当检测不通过时，会执行 {@code onFail} 配置的后续动作，然后结束整个脚本执行。
     *
     * @param variable 比对的变量
     * @param op       操作符
     * @param value    比对的值
     * @param onFail   条件不满足时要执行的动作配置
     */
    public ScriptBuilder check(String variable, String op, Object value, Consumer<ScriptBuilder> onFail) {
        flow.add(buildCheckNode(variable, op, value, onFail));
        return this;
    }

    /**
     * 追加一个 {@code in} 操作的判断节点，检查变量值是否在给定的候选列表中。
     * <p>
     * 编译器自动选择最优策略：≤3 项展开为多路比较，>3 项使用 Set.of() 路径。
     *
     * @param variable 比对的变量
     * @param values   候选值列表
     */
    public ScriptBuilder checkIn(String variable, Object... values) {
        ImmutableList<Object> valueList = ImmutableList.copyOf(values);
        flow.add(new FlowNode(FlowNodeType.CHECK, ImmutableMap.<String, Object>builder()
                .put("variable", variable)
                .put("op", "in")
                .put("value", valueList)
                .put("valueList", valueList)
                .build()));
        return this;
    }

    /**
     * 追加一个 {@code between} 范围判断节点，检查数值变量是否在 [low, high] 闭区间内。
     *
     * @param variable 比对的变量（必须为 INT 或 DOUBLE 类型）
     * @param low      下界（含）
     * @param high     上界（含）
     */
    public ScriptBuilder checkBetween(String variable, Number low, Number high) {
        ImmutableList<Object> range = ImmutableList.of(low, high);
        flow.add(new FlowNode(FlowNodeType.CHECK, ImmutableMap.<String, Object>builder()
                .put("variable", variable)
                .put("op", "between")
                .put("value", range)
                .put("valueList", range)
                .build()));
        return this;
    }

    /**
     * 内部统一构建 CHECK FlowNode 的辅助方法，与 {@link CheckNodeHandler#parse} 保持一致。
     */
    private FlowNode buildCheckNode(String variable, String op, Object value, Consumer<ScriptBuilder> onFail) {
        ImmutableMap.Builder<String, Object> attrs = ImmutableMap.builder();

        FlowNode baseNode = buildCheckNodeInternal(variable, op, value);
        attrs.putAll(baseNode.attrs());

        if (onFail != null) {
            ScriptBuilder failBuilder = new ScriptBuilder(payloadClazz);
            failBuilder.id(this.scriptId + "-fail");
            failBuilder.actionRegistry = this.actionRegistry;
            onFail.accept(failBuilder);
            attrs.put("onFailNodes", failBuilder.flow.build());
        }

        return new FlowNode(FlowNodeType.CHECK, attrs.build(), baseNode.numericValue(), 0);
    }

    private static FlowNode buildCheckNodeInternal(String variable, String op, Object value) {
        // 前置校验：strip '!' 前缀后检查操作符合法性
        validateOperator(op);

        ImmutableMap.Builder<String, Object> attrs = ImmutableMap.builder();
        attrs.put("variable", variable);
        attrs.put("op", op);

        double numericValue = 0.0;
        if (value != null) {
            Object normalizedValue = value;
            if (value instanceof String s) {
                Object parsed = ScriptParser.ValueParser.parseNumber(s);
                if (parsed instanceof Number) {
                    normalizedValue = parsed;
                }
            }
            attrs.put("value", normalizedValue);
            attrs.put("valueType", ScriptParser.ValueParser.inferType(normalizedValue));
            if (normalizedValue instanceof Number n) {
                numericValue = n.doubleValue();
            }
        }
        return new FlowNode(FlowNodeType.CHECK, attrs.build(), numericValue, 0);
    }

    // ======================== COMPOSITE CHECK 节点 ========================

    /**
     * 追加一个 ANY (OR) 复合判断节点。子条件任一成立即通过。
     */
    public ScriptBuilder checkAny(Consumer<ConditionBuilder> anyBuilder) {
        flow.add(buildCompositeNode(FlowNodeType.ANY, anyBuilder, null));
        return this;
    }

    public ScriptBuilder checkAny(Consumer<ConditionBuilder> anyBuilder, Consumer<ScriptBuilder> onFail) {
        flow.add(buildCompositeNode(FlowNodeType.ANY, anyBuilder, onFail));
        return this;
    }

    /**
     * 追加一个 ALL (AND) 复合判断节点。子条件必须全部成立才通过。
     */
    public ScriptBuilder checkAll(Consumer<ConditionBuilder> allBuilder) {
        flow.add(buildCompositeNode(FlowNodeType.ALL, allBuilder, null));
        return this;
    }

    public ScriptBuilder checkAll(Consumer<ConditionBuilder> allBuilder, Consumer<ScriptBuilder> onFail) {
        flow.add(buildCompositeNode(FlowNodeType.ALL, allBuilder, onFail));
        return this;
    }

    private FlowNode buildCompositeNode(FlowNodeType type, Consumer<ConditionBuilder> builderOpt,
            Consumer<ScriptBuilder> onFail) {
        ConditionBuilder cb = new ConditionBuilder();
        builderOpt.accept(cb);

        ImmutableMap.Builder<String, Object> attrs = ImmutableMap.builder();
        attrs.put("children", cb.children.build());

        if (onFail != null) {
            ScriptBuilder failBuilder = new ScriptBuilder(payloadClazz);
            failBuilder.id(this.scriptId + "-fail");
            failBuilder.actionRegistry = this.actionRegistry;
            onFail.accept(failBuilder);
            attrs.put("onFailNodes", failBuilder.flow.build());
        }

        return new FlowNode(type, attrs.build());
    }

    /**
     * 用于构建复合条件子项的建造器。
     */
    public static final class ConditionBuilder {
        private final ImmutableList.Builder<FlowNode> children = ImmutableList.builder();

        private ConditionBuilder() {
        }

        public ConditionBuilder check(String variable, String op, Object value) {
            children.add(buildCheckNodeInternal(variable, op, value));
            return this;
        }

        public ConditionBuilder check(String variable, String op) {
            children.add(buildCheckNodeInternal(variable, op, null));
            return this;
        }

        public ConditionBuilder any(Consumer<ConditionBuilder> anyBuilder) {
            ConditionBuilder cb = new ConditionBuilder();
            anyBuilder.accept(cb);
            children.add(new FlowNode(FlowNodeType.ANY, ImmutableMap.of("children", cb.children.build())));
            return this;
        }

        public ConditionBuilder all(Consumer<ConditionBuilder> allBuilder) {
            ConditionBuilder cb = new ConditionBuilder();
            allBuilder.accept(cb);
            children.add(new FlowNode(FlowNodeType.ALL, ImmutableMap.of("children", cb.children.build())));
            return this;
        }
    }

    // ======================== ACTION 节点 ========================

    /**
     * 追加一个要触发的动作节点（Action）。
     *
     * @param actionName 在 ActionRegistry 中已经注册好的 @ScriptAction 的名字（比如
     *                   "sendMessage"）
     * @param args       顺序填入的参数列表（支持 {@code "{变量名}"} 的模板插值法）
     */
    public ScriptBuilder action(String actionName, Object... args) {
        ActionDef def = requireRegistry().lookup(actionName);
        ImmutableList<String> argsList = toStringArgs(args);
        validateActionArgs(actionName, def, argsList);
        flow.add(new FlowNode(FlowNodeType.ACTION, ImmutableMap.<String, Object>builder()
                .put("action", actionName)
                .put("args", argsList)
                .put("def", def)
                .build()));
        return this;
    }

    /**
     * 追加一个要触发的动作节点（Action）并捕获它的返回值。
     * 引擎编译期会自动识别该 action 的返回值类型，并为你开辟这个储值槽。
     *
     * @param store      捕获返回值的局部变量名称
     * @param actionName 在 ActionRegistry 中已经注册好的 @ScriptAction 名字
     * @param args       顺序填入的参数列表
     */
    public ScriptBuilder actionStore(String store, String actionName, Object... args) {
        ActionDef def = requireRegistry().lookup(actionName);
        ImmutableList<String> argsList = toStringArgs(args);
        validateActionArgs(actionName, def, argsList);

        // 验证返回值（与 ActionNodeHandler.parse 对齐）
        if (def.returnType() == void.class || def.returnType() == Void.class) {
            throw cn.warriorview.script.core.ScriptCompileException.create(
                    null, null,
                    cn.warriorview.script.diagnostic.DiagnosticCategory.SEMANTIC,
                    String.format("Action '%s' does not return a value, cannot store to '%s'", actionName, store));
        }

        IRType returnIRType = IRType.fromClass(def.returnType());

        flow.add(new FlowNode(FlowNodeType.ACTION, ImmutableMap.<String, Object>builder()
                .put("store", store)
                .put("action", actionName)
                .put("args", argsList)
                .put("def", def)
                .put("returnType", returnIRType)
                .build()));
        return this;
    }

    // ======================== RETURN 节点 ========================

    /**
     * 流程提前终止，等价于 {@code return null}。
     */
    public ScriptBuilder returnEarly() {
        flow.add(new FlowNode(FlowNodeType.RETURN, ImmutableMap.of()));
        return this;
    }

    /**
     * 返回字符串字面量、变量或模板字符串。
     * <ul>
     * <li>{@code "{dmg}"} → 返回变量值（自动识别）</li>
     * <li>{@code "HP:{hp} 伤:{dmg}"} → invokedynamic 模板拼接</li>
     * <li>{@code "固定文本"} → 字符串字面量</li>
     * </ul>
     */
    public ScriptBuilder returnValue(String value) {
        flow.add(new FlowNode(FlowNodeType.RETURN, ImmutableMap.of("value", value)));
        return this;
    }

    /** 返回整数字面量。 */
    public ScriptBuilder returnValue(int value) {
        flow.add(new FlowNode(FlowNodeType.RETURN, ImmutableMap.of("value", value)));
        return this;
    }

    /** 返回浮点字面量。 */
    public ScriptBuilder returnValue(double value) {
        flow.add(new FlowNode(FlowNodeType.RETURN, ImmutableMap.of("value", value)));
        return this;
    }

    /** 返回布尔字面量。 */
    public ScriptBuilder returnValue(boolean value) {
        flow.add(new FlowNode(FlowNodeType.RETURN, ImmutableMap.of("value", value)));
        return this;
    }

    /**
     * 直接返回变量的原始值（零开销路径）。
     * <p>
     * 与 {@code returnValue("{varName}")} 不同，此方法走 {@code variable} 属性路径，
     * 绕过字符串模板解析，直接加载局部变量并装箱返回。
     *
     * @param varName 已通过 {@link #defineVar} 声明的变量名
     */
    public ScriptBuilder returnVar(String varName) {
        flow.add(new FlowNode(FlowNodeType.RETURN, ImmutableMap.of("variable", varName)));
        return this;
    }

    /**
     * 返回集合字面量。
     * <p>
     * 每个元素支持：字面量、{@code "{变量名}"} 模板插值、或模板字符串。
     * 编译后通过 {@code List.of(Object...)} 构造不可变列表。
     *
     * @param elements 集合元素（支持 String / Number / Boolean 及变量模板）
     */
    public ScriptBuilder returnList(Object... elements) {
        ImmutableList<Object> list = ImmutableList.copyOf(elements);
        flow.add(new FlowNode(FlowNodeType.RETURN, ImmutableMap.of("value", list)));
        return this;
    }

    // ======================== SWITCH 节点 ========================

    /**
     * 追加一个多分支选择节点（Switch）。
     *
     * @param variable 比对的变量
     * @param config   分支流程构造器
     */
    public ScriptBuilder switchBranch(String variable, Consumer<SwitchBuilder> config) {
        SwitchBuilder builder = new SwitchBuilder(payloadClazz, actionRegistry);
        config.accept(builder);
        flow.add(new FlowNode(FlowNodeType.SWITCH, ImmutableMap.<String, Object>builder()
                .put("variable", variable)
                .put("cases", builder.buildCases())
                .build()));
        return this;
    }

    /**
     * Switch 分支的内部构造器
     */
    public static final class SwitchBuilder {
        private final Class<?> payloadClazz;
        private final ActionRegistry actionRegistry;
        private final ImmutableMap.Builder<String, ImmutableList<FlowNode>> cases = ImmutableMap.builder();

        private SwitchBuilder(Class<?> payloadClazz, ActionRegistry actionRegistry) {
            this.payloadClazz = payloadClazz;
            this.actionRegistry = actionRegistry;
        }

        /**
         * 构造一个 Case 分支区块（支持 String、Enum 或 Number 自动转字符串底层表示）。
         *
         * @param caseKey       匹配的确切键
         * @param branchBuilder 分支内的脚本流程配置
         */
        public SwitchBuilder caseOf(Object caseKey, Consumer<ScriptBuilder> branchBuilder) {
            ScriptBuilder subBuilder = new ScriptBuilder(payloadClazz);
            subBuilder.id("SwitchCase-" + caseKey);
            subBuilder.actionRegistry = this.actionRegistry;
            branchBuilder.accept(subBuilder);
            cases.put(String.valueOf(caseKey), subBuilder.flow.build());
            return this;
        }

        private ImmutableMap<String, ImmutableList<FlowNode>> buildCases() {
            return cases.build();
        }
    }

    // ======================== 编译 ========================

    /**
     * 编译为副作用型处理器（无返回值场景）。
     *
     * @return 运行速度等同于原生硬编码 Java 代码的回调函数
     */
    public Consumer<Object> compile() {
        return buildCompiledScript(Object.class).newHandler();
    }

    /**
     * 编译为计算函数（脚本包含 {@link FlowNodeType#RETURN} 节点时使用）。
     *
     * @return Function&lt;Object, Object&gt;，入参为 payload 对象，返回值为 RETURN
     *         指定变量的装箱值
     */
    public Function<Object, Object> compileAsFunction() {
        return buildCompiledScript(Object.class).newFunction();
    }

    /**
     * 编译为强类型计算函数。
     * 包含在运行期间零损耗的绝对类型校验机制（在脚本编译阶段实施类型阻断）。
     *
     * @param expectedReturnType 期待输出的返回类型
     * @param <T>                Payload 参数的具体类型（在构建器实例化时确定）
     * @param <R>                返回结果的具体类型
     * @return 强类型 Function，直接跳过 instanceOf 开销
     */
    @SuppressWarnings("unchecked")
    public <T, R> Function<T, R> compileTypedFunction(Class<R> expectedReturnType) {
        return (Function<T, R>) buildCompiledScript(expectedReturnType).newFunction();
    }

    /**
     * 动态编译并直接实例化为指定的零损耗字节码接口（Zero-Boxing Adaptation）。
     * <p>
     * 在后台直接抛弃通用的 Function&lt;Object, Object&gt;。通过反射分析给定接口方法的真实参数与返回值，
     * 利用 ASM 从字节码根源上自适应消除拆开箱损耗（例如直接通过 {@code IRETURN} 返回 int 给
     * {@code ToIntFunction}）。
     *
     * @param expectedInterfaceType 目标单方法接口的 Class，例如
     *                              {@code java.util.function.ToIntFunction.class}
     * @param <T>                   具体接口类型的泛型
     * @return 编译就绪且无拆装箱性能损耗的代理实例
     */
    public <T> T compileInterface(Class<T> expectedInterfaceType) {
        ScriptUnit unit = new ScriptUnit(scriptId, payloadClazz.getName(), 0, vars.build(), flow.build());
        return new CompilationPipeline().compileInterface(unit, expectedInterfaceType);
    }

    private CompiledScript buildCompiledScript(Class<?> expectedReturnType) {
        ScriptUnit unit = new ScriptUnit(scriptId, payloadClazz.getName(), 0, vars.build(), flow.build());
        return new CompilationPipeline().compile(unit, expectedReturnType);
    }

    // ======================== 内部辅助 ========================

    /** 将 Object 可变参数统一转为 String 列表，以对齐 ActionNodeHandler.emit 的期望类型。 */
    private static ImmutableList<String> toStringArgs(Object... args) {
        ImmutableList.Builder<String> builder = ImmutableList.builder();
        for (Object arg : args) {
            builder.add(String.valueOf(arg));
        }
        return builder.build();
    }

    /** 获取已绑定的 ActionRegistry，未绑定则抛异常。 */
    private ActionRegistry requireRegistry() {
        if (actionRegistry == null) {
            throw new IllegalStateException(
                    "ActionRegistry not set. Call withActionRegistry() before using action()/actionStore().");
        }
        return actionRegistry;
    }

    /** 校验参数个数（与 ActionNodeHandler.parse 保持一致）。 */
    private static void validateActionArgs(String actionName, ActionDef def, ImmutableList<String> args) {
        int expectedArgs = def.consumesPayload()
                ? Math.max(0, def.paramCount() - 1)
                : def.paramCount();
        if (args.size() != expectedArgs) {
            throw cn.warriorview.script.core.ScriptCompileException.create(
                    null, null,
                    cn.warriorview.script.diagnostic.DiagnosticCategory.SEMANTIC,
                    String.format("Action '%s' expects %d %s, but got %d.",
                            actionName, expectedArgs,
                            def.consumesPayload()
                                    ? "user argument(s) (payload is auto-injected as first param)"
                                    : "argument(s)",
                            args.size()));
        }
    }

    /**
     * 前置校验操作符合法性。委托给 {@link CheckOp#resolve(String)} 统一处理。
     */
    private static void validateOperator(String op) {
        // CheckOp.resolve 会在操作符不合法时抛出 ScriptCompileException（含拼写建议）
        CheckOp.resolve(op);
    }
}
