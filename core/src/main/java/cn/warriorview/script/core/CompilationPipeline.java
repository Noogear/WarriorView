package cn.warriorview.script.core;

import cn.warriorview.script.codegen.BytecodeCompiler;
import cn.warriorview.script.optimizer.ScriptOptimizer;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 编译管线，串联解析→优化→代码生成的全流程。
 * <p>
 * 内置编译缓存：同一 {@link ScriptIR.ScriptUnit} 多次提交时直接复用已编译结果，
 * 跳过 Parser→Optimizer→ASM→defineClass 整条管线。
 * <p>
 * 使用方式：
 * 
 * <pre>{@code
 * CompilationPipeline pipeline = new CompilationPipeline();
 * CompiledScript script = pipeline.compile(yamlInput);
 * // script.handlerClass() 是编译后的 Consumer<Event>
 * }</pre>
 */
public final class CompilationPipeline {

    /** 编译缓存：ScriptUnit 深度 hash → 已编译结果 */
    private static final ConcurrentHashMap<Integer, CompiledScript> CACHE = new ConcurrentHashMap<>();

    private final ScriptOptimizer optimizer;
    private final BytecodeCompiler compiler;

    public CompilationPipeline() {
        this.optimizer = new ScriptOptimizer();
        this.compiler = new BytecodeCompiler();
    }

    public CompiledScript compile(ScriptIR.ScriptUnit unit) {
        return compile(unit, Object.class);
    }

    /**
     * 编译一个已构建好的抽象语法树（纯 Java 代码无 YAML 依赖）。
     * <p>
     * 相同内容的 {@link ScriptIR.ScriptUnit} 会命中缓存直接返回，跳过完整编译。
     *
     * @param unit               脚本单元中间层表示
     * @param expectedReturnType 用户外部期望获取的强类型返回对象
     * @return 编译结果，包含生成的强类型高性能处理器。
     */
    public CompiledScript compile(ScriptIR.ScriptUnit unit, Class<?> expectedReturnType) {
        Preconditions.checkNotNull(unit, "unit");

        int key = deepHash(unit) * 31 + expectedReturnType.hashCode(); // 加入返回类型以隔离缓存
        CompiledScript cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }

        // 2. 构建编译上下文
        CompilationContext ctx = buildContext(unit);

        // 2.5 变量引用与类型完整性检查
        validateVariableReferences(unit, ctx);
        validateActionParameterTypes(unit, ctx);
        validateReturnType(unit, ctx, expectedReturnType);

        // 3. 优化 IR
        ScriptIR.ScriptUnit optimized = optimizer.optimize(unit, ctx);

        // 4. 生成字节码
        byte[] bytecode = compiler.compile(optimized, ctx);

        // 5. 加载类
        // 直接传递 null 代表委托给底层 JVM 从字节码里自发解析内部全限定类名，安全且防报错。
        ScriptClassLoader loader = new ScriptClassLoader(getClass().getClassLoader());
        Class<?> clazz = loader.define(null, bytecode);

        CompiledScript result = new CompiledScript(optimized, clazz, loader);
        CACHE.put(key, result);
        return result;
    }

    /**
     * 清除指定脚本的编译缓存。
     */
    public static void invalidate(ScriptIR.ScriptUnit unit) {
        CACHE.remove(deepHash(unit));
    }

    /**
     * 清空全部编译缓存（用于配置热重载场景）。
     */
    public static void clearCache() {
        CACHE.clear();
    }

    /**
     * 返回当前缓存条目数（调试用）。
     */
    public static int cacheSize() {
        return CACHE.size();
    }

    private static int deepHash(ScriptIR.ScriptUnit unit) {
        int h = unit.payloadClass().hashCode();
        h = 31 * h + unit.vars().hashCode();
        h = 31 * h + unit.flow().hashCode();
        return h;
    }

    private CompilationContext buildContext(ScriptIR.ScriptUnit unit) {
        try {
            Class<?> payloadClass = Class.forName(unit.payloadClass());
            CompilationContext.Builder builder = CompilationContext.builder(payloadClass);

            Set<String> registeredVars = new HashSet<>();
            for (ScriptIR.VarDecl var : unit.vars()) {
                registeredVars.add(var.name());
                builder.addVar(var.name(), var.type());
            }

            // 自动为带有 store 属性的 Action 开辟存储槽位，免去显式声明的麻烦
            for (ScriptIR.FlowNode node : unit.flow()) {
                if (node.type() == ScriptIR.FlowNodeType.ACTION) {
                    String store = node.getAttrOrDefault("store", null);
                    if (store != null) {
                        if (!registeredVars.add(store)) {
                            throw new ScriptCompileException(
                                    "Duplicate store variable name: '" + store + "'");
                        }
                        ScriptIR.IRType type = node.getRequiredAttr("returnType");
                        builder.addVar(store, type);
                    }
                }
            }

            return builder.build();
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("Payload class not found: " + unit.payloadClass(), e);
        }
    }

    /**
     * AOT 变量引用完整性检查。
     * <p>
     * 在优化前扫描所有节点，验证被消费的变量（VariableConsumer、模板字符串）在编译上下文中存在。
     */
    private void validateVariableReferences(ScriptIR.ScriptUnit unit, CompilationContext ctx) {
        for (ScriptIR.FlowNode node : unit.flow()) {
            validateNodeVarRefs(node, ctx);
        }
    }

    private void validateNodeVarRefs(ScriptIR.FlowNode node, CompilationContext ctx) {
        ScriptIR.FlowNodeHandler handler = node.type().handler();

        // 1. 统一处理所有节点汇报的消费变量引用
        if (handler instanceof ScriptIR.VariableConsumer consumer) {
            for (String var : consumer.getAllConsumedVariables(node)) {
                assertVarExists(var, node, ctx);
            }
        }

        // 2. 递归检查子节点
        if (handler instanceof ScriptIR.NodeTraverser traverser) {
            for (ScriptIR.FlowNode child : traverser.traverseChildren(node)) {
                validateNodeVarRefs(child, ctx);
            }
        }
    }

    /**
     * 断言变量在编译上下文中存在，否则抛出友好的编译异常。
     */
    private static void assertVarExists(String varName, ScriptIR.FlowNode node, CompilationContext ctx) {
        if ("payload".equals(varName))
            return;
        try {
            ctx.getSlot(varName);
        } catch (IllegalArgumentException e) {
            throw new ScriptCompileException(
                    String.format("Undefined variable '%s' referenced in %s node.", varName, node.type()));
        }
    }

    // ======================== 类型穿透推导 (Type Propagation Pass)
    // ========================

    private void validateActionParameterTypes(ScriptIR.ScriptUnit unit, CompilationContext ctx) {
        for (ScriptIR.FlowNode node : unit.flow()) {
            validateActionTypesInNode(node, ctx);
        }
    }

    private void validateActionTypesInNode(ScriptIR.FlowNode node, CompilationContext ctx) {
        ScriptIR.FlowNodeHandler handler = node.type().handler();

        // 1. 委托节点处理器进行自己的类型匹配校验
        if (handler instanceof ScriptIR.TypeValidator validator) {
            validator.validateTypes(node, ctx);
        }

        // 2. 递归校验子节点（复合条件 / onFail 等）
        if (handler instanceof ScriptIR.NodeTraverser traverser) {
            for (ScriptIR.FlowNode child : traverser.traverseChildren(node)) {
                validateActionTypesInNode(child, ctx);
            }
        }
    }

    // ======================== 返回类型检查 ========================

    private void validateReturnType(ScriptIR.ScriptUnit unit, CompilationContext ctx, Class<?> expectedJavaType) {
        if (expectedJavaType == Object.class || expectedJavaType == void.class || expectedJavaType == Void.class) {
            return; // 不约束返回类型
        }

        ScriptIR.IRType expectedIR = ScriptIR.IRType.fromClass(expectedJavaType);
        boolean hasReturn = false;

        for (ScriptIR.FlowNode node : unit.flow()) {
            hasReturn |= checkReturnNodesRecursive(node, ctx, expectedIR, expectedJavaType);
        }

        if (!hasReturn) {
            throw new ScriptCompileException(String.format(
                    "Script intends to return a strongly-typed %s, but no explicit RETURN node was found.",
                    expectedJavaType.getSimpleName()));
        }
    }

    private boolean checkReturnNodesRecursive(ScriptIR.FlowNode node, CompilationContext ctx,
            ScriptIR.IRType expectedIR, Class<?> expectedJavaType) {
        boolean found = false;

        if (node.type() == ScriptIR.FlowNodeType.RETURN) {
            found = true;
            String varName = node.getAttrOrDefault("variable", null);
            ScriptIR.IRType actualIR = (varName == null) ? ScriptIR.IRType.OBJECT : ctx.getType(varName);

            // 如果节点指定了返回变量，并且该变量的类型不兼容
            if (varName != null && !expectedIR.isAssignableFrom(actualIR)) {
                throw new ScriptCompileException(String.format(
                        "Script compiled for strict return type %s, but RETURN node provides variable '{%s}' of type %s.",
                        expectedJavaType.getSimpleName(), varName, actualIR));
            }
        }

        ScriptIR.FlowNodeHandler handler = node.type().handler();
        if (handler instanceof ScriptIR.NodeTraverser traverser) {
            for (ScriptIR.FlowNode child : traverser.traverseChildren(node)) {
                found |= checkReturnNodesRecursive(child, ctx, expectedIR, expectedJavaType);
            }
        }
        return found;
    }

    // ======================== 编译结果 ========================

    /**
     * 编译结果。
     */
    public record CompiledScript(
            ScriptIR.ScriptUnit ir,
            Class<?> handlerClass,
            ScriptClassLoader loader) {

        /**
         * 创建 Consumer「副作用型」处理器实例。
         * 内部实际为 Function，包装为 Consumer 以兼容现有 API。
         */
        public Consumer<Object> newHandler() {
            Function<Object, Object> func = newFunction();
            return func::apply; // 方法引用包装，零额外开销
        }

        /**
         * 创建计算型处理器实例。
         * 膀本返回 null（void RETURN），有值返回装箱后的变量（RETURN_VALUE）。
         */
        @SuppressWarnings("unchecked")
        public Function<Object, Object> newFunction() {
            try {
                return (Function<Object, Object>) handlerClass.getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Cannot instantiate compiled function", e);
            }
        }
    }

    // ======================== 类加载器 ========================

    /**
     * 脚本专用类加载器，支持动态定义和卸载。
     */
    static final class ScriptClassLoader extends ClassLoader {

        ScriptClassLoader(ClassLoader parent) {
            super(parent);
        }

        /**
         * 定义一个编译后的类。
         */
        Class<?> define(String name, byte[] bytecode) {
            return defineClass(name, bytecode, 0, bytecode.length);
        }
    }
}
