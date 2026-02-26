package cn.warriorview.script.core;

import cn.warriorview.script.codegen.BytecodeCompiler;
import cn.warriorview.script.optimizer.ScriptOptimizer;
import com.google.common.base.Preconditions;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 编译管线，串联解析→优化→代码生成的全流程。
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

    private final ScriptOptimizer optimizer;
    private final BytecodeCompiler compiler;

    public CompilationPipeline() {
        this.optimizer = new ScriptOptimizer();
        this.compiler = new BytecodeCompiler();
    }

    /**
     * 编译一个已构建好的抽象语法树（纯 Java 代码无 YAML 依赖）。
     *
     * @param unit 脚本单元中间层表示
     * @return 编译结果，包含生成的强类型高性能处理器。
     */
    public CompiledScript compile(ScriptIR.ScriptUnit unit) {
        Preconditions.checkNotNull(unit, "unit");

        // 2. 构建编译上下文
        CompilationContext ctx = buildContext(unit);

        // 3. 优化 IR
        ScriptIR.ScriptUnit optimized = optimizer.optimize(unit, ctx);

        // 4. 生成字节码
        byte[] bytecode = compiler.compile(optimized, ctx);

        // 5. 加载类
        // 直接传递 null 代表委托给底层 JVM 从字节码里自发解析内部全限定类名，安全且防报错。
        ScriptClassLoader loader = new ScriptClassLoader(getClass().getClassLoader());
        Class<?> clazz = loader.define(null, bytecode);

        return new CompiledScript(optimized, clazz, loader);
    }

    private CompilationContext buildContext(ScriptIR.ScriptUnit unit) {
        try {
            Class<?> payloadClass = Class.forName(unit.payloadClass());
            CompilationContext.Builder builder = CompilationContext.builder(payloadClass);

            for (ScriptIR.VarDecl var : unit.vars()) {
                builder.addVar(var.name(), var.type());
            }

            // 新增: 自动为带有 store 属性的 Action 开辟存储槽位，免去显式声明的麻烦
            for (ScriptIR.FlowNode node : unit.flow()) {
                if (node.type() == ScriptIR.FlowNodeType.ACTION) {
                    String store = node.getAttrOrDefault("store", null);
                    if (store != null) {
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
