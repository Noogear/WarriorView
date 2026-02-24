package cn.warriorview.script.core;

import cn.warriorview.script.codegen.BytecodeCompiler;
import cn.warriorview.script.optimizer.ScriptOptimizer;
import cn.warriorview.script.parser.ScriptParser;
import com.google.common.base.Preconditions;

import java.io.InputStream;
import java.util.function.Consumer;

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

    private final ScriptParser parser;
    private final ScriptOptimizer optimizer;
    private final BytecodeCompiler compiler;

    public CompilationPipeline() {
        this.parser = new ScriptParser();
        this.optimizer = new ScriptOptimizer();
        this.compiler = new BytecodeCompiler();
    }

    /**
     * 编译一个 YAML 脚本输入流。
     *
     * @return 编译结果，包含生成的事件处理器类。
     */
    public CompiledScript compile(InputStream yamlInput) {
        Preconditions.checkNotNull(yamlInput, "yamlInput");

        // 1. 解析 YAML → IR
        ScriptIR.ScriptUnit unit = parser.parse(yamlInput);
        return compile(unit);
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
        String className = "cn.warriorview.script.generated.Script$"
                + Integer.toHexString(System.identityHashCode(bytecode));
        ScriptClassLoader loader = new ScriptClassLoader(getClass().getClassLoader());
        Class<?> clazz = loader.define(className, bytecode);

        return new CompiledScript(optimized, clazz, loader);
    }

    private CompilationContext buildContext(ScriptIR.ScriptUnit unit) {
        try {
            Class<?> payloadClass = Class.forName(unit.payloadClass());
            CompilationContext.Builder builder = CompilationContext.builder(payloadClass);

            for (ScriptIR.VarDecl var : unit.vars()) {
                builder.addVar(var.name(), var.type());
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
         * 创建事件处理器实例。
         */
        @SuppressWarnings("unchecked")
        public Consumer<Object> newHandler() {
            try {
                return (Consumer<Object>) handlerClass.getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Cannot instantiate compiled handler", e);
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
