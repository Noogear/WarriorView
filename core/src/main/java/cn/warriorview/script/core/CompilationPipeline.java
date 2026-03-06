package cn.warriorview.script.core;

import cn.warriorview.script.codegen.BytecodeCompiler;
import cn.warriorview.script.diagnostic.DiagnosticCategory;
import cn.warriorview.script.optimizer.ScriptOptimizer;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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

    /**
     * 编译缓存：ScriptUnit 深度 hash → 已编译结果（弱引用）。
     * <p>
     * 使用 {@link WeakReference} 确保：当所有由该脚本产生的处理器实例均不可达时，
     * Hidden Class 可被 JVM 从元空间 GC 卸载，无需手动 clearCache。
     */
    private static final ConcurrentHashMap<Integer, WeakReference<CompiledScript>> CACHE = new ConcurrentHashMap<>();

    /**
     * 结构模板缓存：structural hash → 已优化的 IR 模板。
     * <p>
     * 结构相同但常量值不同的脚本共享同一优化结果，
     * 仅需替换常量后重新运行 BytecodeCompiler（跳过全部验证和优化 Pass）。
     */
    private static final ConcurrentHashMap<Integer, TemplateRecord> TEMPLATE_CACHE = new ConcurrentHashMap<>();

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

        int key = deepHash(unit) * 31 + expectedReturnType.hashCode();
        WeakReference<CompiledScript> ref = CACHE.get(key);
        CompiledScript cached = (ref != null) ? ref.get() : null;
        if (cached != null) {
            return new CompiledScript(unit, cached.handlerClass());
        }

        try {
            // ===== 快速路径：结构模板命中 =====
            int structKey = structuralHash(unit) * 31 + expectedReturnType.hashCode();
            TemplateRecord template = TEMPLATE_CACHE.get(structKey);
            if (template != null) {
                return compileFromTemplate(unit, template, key);
            }

            // ===== 完整路径：首次编译 =====
            CompilationContext ctx = buildContext(unit, expectedReturnType);

            primeNarrowings(unit, ctx);
            validateVariableReferences(unit, ctx);
            validateActionParameterTypes(unit, ctx);
            validateReturnType(unit, ctx, expectedReturnType);

            ScriptIR.ScriptUnit optimized = optimizer.optimize(unit, ctx);

            byte[] bytecode = compiler.compile(optimized, ctx);

            Class<?> clazz = defineHidden(bytecode);

            CompiledScript result = new CompiledScript(optimized, clazz);
            CACHE.put(key, new WeakReference<>(result));

            // 注册结构模板（首次编译后缓存优化结果供后续快速路径复用）
            TEMPLATE_CACHE.putIfAbsent(structKey, new TemplateRecord(unit, optimized, ctx, expectedReturnType));

            return result;
        } catch (ScriptCompileException e) {
            throw e;
        } catch (cn.warriorview.script.diagnostic.DiagnosticException e) {
            throw new ScriptCompileException(e.diagnostic(), e);
        } catch (Exception e) {
            throw ScriptCompileException.create(unit.id(), null,
                    "Error compiling script [" + unit.id() + "]: " + e.getMessage());
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
        WeakReference<CompiledScript> ref = CACHE.get(key);
        CompiledScript cached = (ref != null) ? ref.get() : null;
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
            Class<?> clazz = defineHidden(bytecode);

            CompiledScript result = new CompiledScript(optimized, clazz);
            CACHE.put(key, new WeakReference<>(result));

            return result.newInstance(unit.id());
        } catch (ScriptCompileException e) {
            throw e;
        } catch (cn.warriorview.script.diagnostic.DiagnosticException e) {
            throw new ScriptCompileException(e.diagnostic(), e);
        } catch (Exception e) {
            throw ScriptCompileException.create(unit.id(), null,
                    "Error compiling script [" + unit.id() + "]: " + e.getMessage());
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
        TEMPLATE_CACHE.clear();
    }

    /**
     * 返回当前缓存条目数（调试用）。
     */
    public static int cacheSize() {
        return CACHE.size();
    }

    /**
     * 返回结构模板缓存条目数（调试用）。
     */
    public static int templateCacheSize() {
        return TEMPLATE_CACHE.size();
    }

    private static int deepHash(ScriptIR.ScriptUnit unit) {
        int h = unit.payloadClass().hashCode();
        h = 31 * h + unit.vars().hashCode();
        h = 31 * h + unit.flow().hashCode();
        return h;
    }

    // ======================== 结构模板快速路径 ========================

    /**
     * 结构模板记录。
     * <p>
     * 存储首次完整编译的优化后 IR 和上下文，供后续结构相同的脚本快速复用。
     */
    private record TemplateRecord(
            ScriptIR.ScriptUnit originalUnit,
            ScriptIR.ScriptUnit optimizedUnit,
            CompilationContext ctx,
            Class<?> expectedReturnType) {
    }

    /**
     * 计算脚本的"结构哈希"——只哈希节点类型、变量名、操作符等结构信息，
     * 忽略字面量值（numericValue、attrs 中的 value/args）。
     * <p>
     * 结构哈希相同的脚本可以通过常量替换复用已优化的 IR。
     */
    static int structuralHash(ScriptIR.ScriptUnit unit) {
        int h = unit.payloadClass().hashCode();
        // vars 的结构部分：名字 + 属性链 + 类型
        for (ScriptIR.VarDecl v : unit.vars()) {
            h = 31 * h + v.name().hashCode();
            h = 31 * h + v.property().hashCode();
            h = 31 * h + v.type().hashCode();
        }
        // flow 的结构部分
        for (ScriptIR.FlowNode node : unit.flow()) {
            h = 31 * h + structuralHashNode(node);
        }
        return h;
    }

    /**
     * 递归计算单个节点的结构哈希。
     * 忽略: numericValue, attrs["value"], attrs["args"], attrs["valueList"],
     * attrs["__line__"]
     */
    private static int structuralHashNode(ScriptIR.FlowNode node) {
        int h = node.type().hashCode();
        for (Map.Entry<String, Object> entry : node.attrs().entrySet()) {
            String key = entry.getKey();
            // 跳过字面量值和行号——这些不影响结构
            if ("value".equals(key) || "args".equals(key) || "valueList".equals(key)
                    || "__line__".equals(key) || "valueType".equals(key)) {
                continue;
            }
            h = 31 * h + key.hashCode();
            Object val = entry.getValue();
            // 递归处理子节点列表（如 onFailNodes, children, cases）
            if (val instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof ScriptIR.FlowNode) {
                for (Object item : list) {
                    h = 31 * h + structuralHashNode((ScriptIR.FlowNode) item);
                }
            } else if (val instanceof ScriptIR.FlowNode childNode) {
                h = 31 * h + structuralHashNode(childNode);
            } else if (val != null) {
                h = 31 * h + val.hashCode();
            }
        }
        return h;
    }

    /**
     * 从模板快速编译：用新脚本的常量值替换模板 IR 中的常量，跳过全部验证和优化。
     */
    private CompiledScript compileFromTemplate(ScriptIR.ScriptUnit newUnit, TemplateRecord template, int cacheKey) {
        try {
            // 1. 将新脚本的常量值替换进已优化的 IR
            ScriptIR.ScriptUnit substituted = substituteConstants(template.optimizedUnit(), template.originalUnit(),
                    newUnit);

            // 2. 重建编译上下文（使用与模板相同的 expectedReturnType）
            CompilationContext freshCtx = buildContext(newUnit, template.expectedReturnType());
            // 复制分析 Pass 结果
            freshCtx.setHoistedConstants(template.ctx().hoistedConstants());
            freshCtx.setLiveVars(template.ctx().liveVars());

            // 3. 直接生成字节码（跳过 8 个优化 Pass + 全部验证）
            byte[] bytecode = compiler.compile(substituted, freshCtx);

            // 4. 加载
            Class<?> clazz = defineHidden(bytecode);

            CompiledScript result = new CompiledScript(substituted, clazz);
            CACHE.put(cacheKey, new WeakReference<>(result));
            return result;
        } catch (Exception e) {
            // 模板路径出错时安全回退到完整编译
            TEMPLATE_CACHE.remove(structuralHash(newUnit) * 31
                    + (template.expectedReturnType() != null ? template.expectedReturnType().hashCode() : 0));
            return compileFull(newUnit,
                    template.expectedReturnType() != null ? template.expectedReturnType() : Object.class);
        }
    }

    /**
     * 完整编译路径（用于模板回退）。
     */
    private CompiledScript compileFull(ScriptIR.ScriptUnit unit, Class<?> expectedReturnType) {
        CompilationContext ctx = buildContext(unit, expectedReturnType);
        primeNarrowings(unit, ctx);
        validateVariableReferences(unit, ctx);
        validateActionParameterTypes(unit, ctx);
        validateReturnType(unit, ctx, expectedReturnType);
        ScriptIR.ScriptUnit optimized = optimizer.optimize(unit, ctx);
        byte[] bytecode = compiler.compile(optimized, ctx);
        Class<?> clazz = defineHidden(bytecode);
        return new CompiledScript(optimized, clazz);
    }

    /**
     * 将 newUnit 的常量值替换进 templateOptimized 的对应位置。
     * <p>
     * 使用值映射策略（old value → new value）而非节点键匹配，
     * 使得替换不受优化器变换（variableInlining 移除 variable 属性等）的影响。
     */
    private static ScriptIR.ScriptUnit substituteConstants(
            ScriptIR.ScriptUnit templateOptimized,
            ScriptIR.ScriptUnit templateOriginal,
            ScriptIR.ScriptUnit newUnit) {

        ImmutableList<ScriptIR.FlowNode> origFlow = templateOriginal.flow();
        ImmutableList<ScriptIR.FlowNode> newFlow = newUnit.flow();
        ImmutableList<ScriptIR.FlowNode> optFlow = templateOptimized.flow();

        if (origFlow.size() != newFlow.size()) {
            throw new IllegalStateException("Structural mismatch: flow size differs");
        }

        // 1. 构建值映射：oldValue → newValue
        Map<Double, Double> numericSubs = new java.util.LinkedHashMap<>();
        // key: attrKey, value: oldVal→newVal
        Map<String, Map<Object, Object>> attrSubs = new java.util.HashMap<>();

        for (int i = 0; i < origFlow.size(); i++) {
            ScriptIR.FlowNode orig = origFlow.get(i);
            ScriptIR.FlowNode repl = newFlow.get(i);

            // 数值映射
            if (Double.compare(orig.numericValue(), repl.numericValue()) != 0
                    && orig.numericValue() != 0.0) { // 跳过默认0值，避免误替换
                numericSubs.put(orig.numericValue(), repl.numericValue());
            }

            // 属性值映射
            for (String key : new String[] { "value", "args", "valueList" }) {
                Object origVal = orig.attrs().get(key);
                Object newVal = repl.attrs().get(key);
                if (origVal != null && newVal != null && !newVal.equals(origVal)) {
                    attrSubs.computeIfAbsent(key, k -> new java.util.LinkedHashMap<>())
                            .put(origVal, newVal);
                }
            }
        }

        if (numericSubs.isEmpty() && attrSubs.isEmpty()) {
            return templateOptimized;
        }

        // 2. 对优化后 IR 的每个节点应用值映射
        ImmutableList.Builder<ScriptIR.FlowNode> builder = ImmutableList.builder();
        for (ScriptIR.FlowNode node : optFlow) {
            if (node.hasFlag(ScriptIR.FlowNode.FLAG_OPTIMIZER_INJECTED)
                    || node.hasFlag(ScriptIR.FlowNode.FLAG_FOLDED)) {
                builder.add(node);
                continue;
            }

            // 替换 numericValue
            Double newNum = numericSubs.get(node.numericValue());
            if (newNum != null) {
                node = node.withNumericValue(newNum);
            }

            // 替换属性值
            for (Map.Entry<String, Map<Object, Object>> sub : attrSubs.entrySet()) {
                String attrKey = sub.getKey();
                Object currentVal = node.attrs().get(attrKey);
                if (currentVal != null) {
                    Object replacement = sub.getValue().get(currentVal);
                    if (replacement != null) {
                        node = node.withAttr(attrKey, replacement);
                    }
                }
            }

            builder.add(node);
        }

        return templateOptimized.withFlow(builder.build());
    }

    private CompilationContext buildContext(ScriptIR.ScriptUnit unit, Class<?> expectedInterfaceType) {
        try {
            Class<?> payloadClass = Class.forName(unit.payloadClass());
            CompilationContext.Builder builder = CompilationContext.builder(payloadClass)
                    .scriptId(unit.id());

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
                            throw ScriptCompileException.create(unit.id(), node,
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
            throw ScriptCompileException.parse("Payload class not found: " + unit.payloadClass());
        }
    }

    private static Method findSAM(Class<?> interfaceClass) {
        Method sam = null;
        for (Method m : interfaceClass.getMethods()) {
            if (java.lang.reflect.Modifier.isAbstract(m.getModifiers())
                    && !m.isDefault()
                    && !isObjectMethod(m)) {
                if (sam != null) {
                    throw ScriptCompileException.parse("Target interface " + interfaceClass.getName()
                            + " is not a single abstract method (SAM) interface.");
                }
                sam = m;
            }
        }
        if (sam == null) {
            throw ScriptCompileException.parse(
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
            String rawOp = node.getAttrOrDefault("op", null);
            if (rawOp == null)
                continue;
            CheckOp.Resolved resolved = CheckOp.resolve(rawOp);
            if (resolved.op() != CheckOp.INSTANCEOF || resolved.negate())
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
            validateNodeVarRefs(node, ctx, unit.id());
        }
    }

    private void validateNodeVarRefs(ScriptIR.FlowNode node, CompilationContext ctx, String scriptId) {
        ScriptIR.FlowNodeHandler handler = node.type().handler();

        // 1. 统一处理所有节点汇报的消费变量引用
        if (handler instanceof ScriptIR.VariableConsumer consumer) {
            for (String var : consumer.getAllConsumedVariables(node)) {
                assertVarExists(var, node, ctx, scriptId);
            }
        }

        // 2. 递归检查子节点
        if (handler instanceof ScriptIR.NodeTraverser traverser) {
            for (ScriptIR.FlowNode child : traverser.traverseChildren(node)) {
                validateNodeVarRefs(child, ctx, scriptId);
            }
        }
    }

    /**
     * 断言变量在编译上下文中存在，否则抛出友好的编译异常。
     */
    private static void assertVarExists(String varName, ScriptIR.FlowNode node,
                                         CompilationContext ctx, String scriptId) {
        if ("payload".equals(varName))
            return;
        try {
            ctx.getSlot(varName);
        } catch (cn.warriorview.script.diagnostic.DiagnosticException e) {
            Object nodeValue = node.getAttrOrDefault("value", null);
            String context = (nodeValue instanceof String s && !s.isEmpty())
                    ? " (in: \"" + s + "\")"
                    : "";
            throw ScriptCompileException.create(scriptId, node,
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
            hasReturn |= checkReturnNodesRecursive(node, ctx, expectedIR, expectedJavaType, unit.id());
        }

        if (!hasReturn) {
            throw ScriptCompileException.create(unit.id(), null, DiagnosticCategory.SEMANTIC,
                    String.format(
                    "Script intends to return a strongly-typed %s, but no explicit RETURN node was found.",
                    expectedJavaType.getSimpleName()));
        }
    }

    private boolean checkReturnNodesRecursive(ScriptIR.FlowNode node, CompilationContext ctx,
            ScriptIR.IRType expectedIR, Class<?> expectedJavaType, String scriptId) {
        boolean found = false;

        if (node.type() == ScriptIR.FlowNodeType.RETURN) {
            found = true;
            String varName = node.getAttrOrDefault("variable", null);
            ScriptIR.IRType actualIR = (varName == null) ? ScriptIR.IRType.OBJECT : ctx.getType(varName);

            // 如果节点指定了返回变量，并且该变量的类型不兼容
            if (varName != null && !expectedIR.isAssignableFrom(actualIR)) {
                throw ScriptCompileException.create(scriptId, node,
                        cn.warriorview.script.diagnostic.DiagnosticCategory.TYPE, String.format(
                        "Script compiled for strict return type %s, but RETURN node provides variable '{%s}' of type %s.",
                        expectedJavaType.getSimpleName(), varName, actualIR));
            }
        }

        ScriptIR.FlowNodeHandler handler = node.type().handler();
        if (handler instanceof ScriptIR.NodeTraverser traverser) {
            for (ScriptIR.FlowNode child : traverser.traverseChildren(node)) {
                found |= checkReturnNodesRecursive(child, ctx, expectedIR, expectedJavaType, scriptId);
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
            Class<?> handlerClass) {

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

    // ======================== Hidden Class 定义 ========================

    /**
     * 委托 {@link cn.warriorview.script.codegen.generated.GeneratedScriptHost#defineHidden}
     * 在 {@code codegen.generated} 包内定义隐藏类。
     */
    private static Class<?> defineHidden(byte[] bytecode) {
        return cn.warriorview.script.codegen.generated.GeneratedScriptHost.defineHidden(bytecode);
    }
}
