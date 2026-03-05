package cn.warriorview.script.codegen.generated;

import java.lang.invoke.MethodHandles;

/**
 * 生成类的包锚点（package anchor）。
 * <p>
 * 此类本身没有任何业务逻辑，唯一的作用是在
 * {@code cn.warriorview.script.codegen.generated} 包内捕获一个
 * {@link MethodHandles.Lookup}，供 {@link cn.warriorview.script.core.CompilationPipeline}
 * 通过 {@code defineHiddenClass} 在本包内加载生成的脚本类。
 * <p>
 * <b>为什么需要这个类？</b><br>
 * {@code defineHiddenClass} 要求字节码中声明的包名与 Lookup 宿主类的包名完全一致，
 * 否则抛 {@link IllegalArgumentException}。通过在目标包内放置一个锚点类来捕获
 * Lookup，可以让生成类使用具有语义的子包名，而无需修改 Lookup 的捕获位置。
 */
public final class GeneratedScriptHost {

    private GeneratedScriptHost() {}

    /**
     * 在 {@code cn.warriorview.script.codegen.generated} 包内捕获的 Lookup。
     * <p>
     * 生成类的内部名格式：{@code cn/warriorview/script/codegen/generated/Script$<hash>}。
     */
    public static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

    /**
     * 生成类的包前缀（JVM 内部名格式），供 {@link cn.warriorview.script.codegen.BytecodeCompiler}
     * 构造与本 Lookup 同包的类名。
     */
    public static final String PACKAGE_PREFIX =
            GeneratedScriptHost.class.getPackage().getName().replace('.', '/');

    /**
     * 使用 {@link MethodHandles.Lookup#defineHiddenClass} 在本包内定义隐藏类。
     * <p>
     * 与传统 {@code ClassLoader.defineClass} 相比，隐藏类的主要优势：
     * <ul>
     *   <li>无需为每个脚本分配独立的 {@link ClassLoader}，消除 ClassLoader 本身的元空间开销</li>
     *   <li>当该类的所有实例均不可达时，JVM 可自动将其从元空间 GC 卸载</li>
     *   <li>配合 {@link java.lang.ref.WeakReference} 缓存，实现无感知的元空间自动回收</li>
     * </ul>
     *
     * @param bytecode 由 {@link cn.warriorview.script.codegen.BytecodeCompiler} 生成的类字节码
     * @return 定义后的隐藏类
     */
    public static Class<?> defineHidden(byte[] bytecode) {
        try {
            return LOOKUP
                    .defineHiddenClass(bytecode, true)
                    .lookupClass();
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Failed to define hidden script class", e);
        }
    }
}
