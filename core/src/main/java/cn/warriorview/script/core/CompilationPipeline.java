package cn.warriorview.script.core;

import cn.warriorview.script.codegen.BytecodeCompiler;
import cn.warriorview.script.optimizer.ScriptOptimizer;
import com.google.common.base.Preconditions;

import java.lang.reflect.Method;
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
            return new CompiledScript(unit, cached.handlerClass(), cached.loader());
        }

        try {
            // 2. 构建编译上下文
            // 当 expectedReturnType 不是接口时，代表默认的 ScriptUnit / 返回值校验模式，不改变字节码目标接口
            CompilationContext ctx = buildContext(unit, expectedReturnType);

            // 2.5 变量引用与类型完整性检查
            primeNarrowings(unit, ctx);
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
        } catch (Exception e) {
            String msg = e.getMessage();
            if (e instanceof ScriptCompileException && msg != null && msg.startsWith("Error compiling script")) {
                throw (ScriptCompileException) e;
            }
            throw new ScriptCompileException("Error compiling script [" + unit.id() + "]: " + msg, e);
        }
    }

    /**
     * 动态接口自适应编译。根据用户传入的目标 SAM 接口动态生成无装箱字节码。
     */
    public <T> T compileInterface(ScriptIR.ScriptUnit unit, Class<T> expectedInterfaceType) {
        Preconditions.checkNotNull(unit, "unit");
        Preconditions.checkNotNull(expectedInterfaceType, "expectedInterfaceType");
        Preconditions.checkArgument(expectedInterfaceType.isInterface(), "target must be an interface");

        Method sam = findSAM(expectedInterfaceType);
        Class<?> expectedReturnType = sam.getReturnType();

        int key = deepHash(unit) * 31 + expectedInterfaceType.hashCode();
        CompiledScript cached = CACHE.get(key);
        if (cached != null) {
            return cached.newInstance(unit.id());
        }

        try {
            CompilationContext ctx = buildContext(unit, expectedInterfaceType);

            primeNarrowings(unit, ctx);
            validateVariableReferences(unit, ctx);
            validateActionParameterTypes(unit, ctx);
            validateReturnType(unit, ctx, expectedReturnType);

            ScriptIR.ScriptUnit optimized = optimizer.optimize(unit, ctx);
            byte[] bytecode = compiler.compile(optimized, ctx);
            ScriptClassLoader loader = new ScriptClassLoader(getClass().getClassLoader());
            Class<?> clazz = loader.define(null, bytecode);

            CompiledScript result = new CompiledScript(optimized, clazz, loader);
            CACHE.put(key, result);

            return result.newInstance(unit.id());
        } catch (Exception e) {
            String msg = e.getMessage();
            if (e instanceof ScriptCompileException && msg != null && msg.startsWith("Error compiling script")) {
                throw (ScriptCompileException) e;
            }
            throw new ScriptCompileException("Error compiling script [" + unit.id() + "]: " + msg, e);
        }
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

    private CompilationContext buildContext(ScriptIR.ScriptUnit unit, Class<?> expectedInterfaceType) {
        try {
            Class<?> payloadClass = Class.forName(unit.payloadClass());
            CompilationContext.Builder builder = CompilationContext.builder(payloadClass);

            if (expectedInterfaceType != null && expectedInterfaceType.isInterface()) {
                Method sam = findSAM(expectedInterfaceType);
                builder.targetMethod(
                        org.objectweb.asm.Type.getInternalName(expectedInterfaceType),
                        sam.getName(),
                        org.objectweb.asm.Type.getMethodDescriptor(sam),
                        org.objectweb.asm.Type.getReturnType(sam));
            }

            Set<String> registeredVars = new HashSet<>();
            for (ScriptIR.VarDecl var : unit.vars()) {
                registeredVars.add(var.name());
                if (var.isPayloadAlias()) {
                    // 别名直接映射到 slot 1，类型为 payload 具体类
                    builder.addPayloadAlias(var.name(), ScriptIR.IRType.fromClass(payloadClass));
                } else {
                    builder.addVar(var.name(), var.type());
                }
            }

            // 自动为所有会产生局部变量的节点（如 Action, Math 等 VariableProducer）开辟存储槽位，免去显式声明的麻烦
            for (ScriptIR.FlowNode node : unit.flow()) {
                ScriptIR.FlowNodeHandler handler = node.type().handler();
                if (handler instanceof ScriptIR.VariableProducer producer) {
                    String store = producer.getProducedVariable(node);
                    if (store != null) {
                        if (!registeredVars.add(store)) {
                            throw new ScriptCompileException(
                                    "Duplicate store variable name: '" + store + "'");
                        }
                        ScriptIR.IRType type;
                        if (node.type() == cn.warriorview.script.core.ScriptIR.FlowNodeType.ACTION) {
                            type = node.getRequiredAttr("returnType");
                        } else if (node.type() == cn.warriorview.script.core.ScriptIR.FlowNodeType.MATH) {
                            type = cn.warriorview.script.core.ScriptIR.IRType.DOUBLE;
                        } else {
                            type = node.getAttrOrDefault("returnType",
                                    cn.warriorview.script.core.ScriptIR.IRType.OBJECT);
                        }
                        builder.addVar(store, type);
                    }
                }
            }

            return builder.build();
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("Payload class not found: " + unit.payloadClass(), e);
        }
    }

    private static Method findSAM(Class<?> interfaceClass) {
        Method sam = null;
        for (Method m : interfaceClass.getMethods()) {
            if (java.lang.reflect.Modifier.isAbstract(m.getModifiers())
                    && !m.isDefault()
                    && !isObjectMethod(m)) {
                if (sam != null) {
                    throw new IllegalArgumentException("Target interface " + interfaceClass.getName()
                            + " is not a single abstract method (SAM) interface.");
                }
                sam = m;
            }
        }
        if (sam == null) {
            throw new IllegalArgumentException(
                    "Target interface " + interfaceClass.getName() + " has no abstract method.");
        }
        return sam;
    }

    private static boolean isObjectMethod(Method m) {
        try {
            Object.class.getMethod(m.getName(), m.getParameterTypes());
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    /**
     * 预扫描所有顶层 check 节点，将 instanceof（非取反）产生的窄化提前注册进 CompilationContext，
     * 使验证阶段（validateActionParameterTypes）可以感知到窄化类型。
     */
    private void primeNarrowings(ScriptIR.ScriptUnit unit, CompilationContext ctx) {
        for (ScriptIR.FlowNode node : unit.flow()) {
            if (node.type() != ScriptIR.FlowNodeType.CHECK)
                continue;
            String op = node.getAttrOrDefault("op", null);
            if (op == null || op.startsWith("!") || !"instanceof".equals(op))
                continue;
            String variable = node.getAttrOrDefault("variable", null);
            String rawClass = node.getAttrOrDefault("value", null);
            if (variable == null || rawClass == null)
                continue;
            try {
                ctx.narrowType(variable, Class.forName(rawClass.replace('/', '.')));
            } catch (ClassNotFoundException e) {
                // 未找到类时静默忽略，正式编译阶段会再次校验并报错
            }
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
            // 如果节点存有 value 文本（如模板字符串），附加到错误信息中方便定位
            // 例：旧信息 "Undefined variable 'lv:'" 现在会显示为
            //     "Undefined variable 'lv:' referenced in RETURN node (in: \"lv:{lvl} sc:{score}\")"
            // 让开发者立刻看出 'lv:' 是字面量被误判，而非真正的变量名
            Object nodeValue = node.getAttrOrDefault("value", null);
            String context = (nodeValue instanceof String s && !s.isEmpty())
                    ? " (in: \"" + s + "\")"
                    : "";
            throw new ScriptCompileException(
                    String.format("Undefined variable '%s' referenced in %s node%s.",
                            varName, node.type(), context));
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
         * 动态实例化（用于零装箱等纯粹动态匹配）。
         */
        @SuppressWarnings("unchecked")
        public <T> T newInstance(String scriptId) {
            try {
                return (T) handlerClass.getDeclaredConstructor(String.class).newInstance(scriptId);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Cannot instantiate compiled function for script: " + scriptId, e);
            }
        }

        /**
         * 创建计算型处理器实例。
         * 膀本返回 null（void RETURN），有值返回装箱后的变量（RETURN_VALUE）。
         */
        public Function<Object, Object> newFunction() {
            return newInstance(ir.id());
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
