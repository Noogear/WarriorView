package cn.warriorview.script.codegen;

import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 脚本常量引导器（外置常量池）。
 * <p>
 * 替代每个 Hidden Class 内部的 {@code static final} 字段 + {@code <clinit>} 初始化，
 * 将 Pattern / Set / 数组等重量常量外置到本类的全局注册表，通过 {@code invokedynamic} 访问。
 * <p>
 * 元空间优势：
 * <ul>
 *   <li>消除每个脚本类的字段元数据（每个 {@code static final} 字段约 50B）</li>
 *   <li>消除每个脚本类的 {@code <clinit>} 方法体（每个常量约 40–80B）</li>
 *   <li>相同内容的常量跨脚本共享同一 {@link ConstantCallSite}，零重复元空间开销</li>
 * </ul>
 * <p>
 * 运行期性能：{@link ConstantCallSite} 首次链接后 JIT 将调用点折叠为纯常量引用，
 * 与 {@code GETSTATIC static final} 等价，无额外开销。
 */
public final class ScriptConstantBootstrap {

    private ScriptConstantBootstrap() {}

    /** 全局常量注册表：内容哈希键 → 常量实例（Pattern / Set / 数组） */
    private static final ConcurrentHashMap<String, Object> REGISTRY = new ConcurrentHashMap<>();

    /**
     * 批量注册提升后的常量列表。由 {@link BytecodeCompiler} 在生成字节码前调用。
     * <p>
     * 根据 {@link cn.warriorview.script.core.CompilationContext.ConstantKind} 构建常量实例
     * 并写入 {@link #REGISTRY}。{@code putIfAbsent} 保证跨脚本相同内容的常量共享同一实例。
     *
     * @param constants 由优化器提升的常量定义列表
     */
    @SuppressWarnings("unchecked")
    static void registerAll(java.util.List<cn.warriorview.script.core.CompilationContext.ConstantDef> constants) {
        for (var def : constants) {
            Object instance = switch (def.kind()) {
                case PATTERN -> java.util.regex.Pattern.compile((String) def.value());
                case STRING_SET -> {
                    com.google.common.collect.ImmutableList<Object> vals =
                            (com.google.common.collect.ImmutableList<Object>) def.value();
                    yield java.util.Set.of(vals.stream().map(Object::toString).toArray());
                }
                case DOUBLE_ARRAY, INT_ARRAY -> def.value();
            };
            REGISTRY.putIfAbsent(def.key(), instance);
        }
    }

    /**
     * 注册单个常量（仅供测试使用）。生产代码统一走 {@link #registerAll}。
     */
    static void register(String key, Object value) {
        REGISTRY.putIfAbsent(key, value);
    }

    /**
     * {@code invokedynamic} 引导方法。
     * <p>
     * JVM 在第一次执行对应 {@code invokedynamic} 指令时调用此方法，
     * 从 {@link #REGISTRY} 取出预注册的常量，返回 {@link ConstantCallSite}。
     * JIT 随后将该调用点折叠为常量引用，后续调用开销与 {@code GETSTATIC static final} 相同。
     *
     * @param lookup 调用点的查找对象（JVM 自动传入）
     * @param name   调用点名称（即 {@code invokedynamic} 的方法名，调试用）
     * @param type   方法类型，返回值为常量的实际类型
     * @param key    常量键，与 {@link #register} 使用的 key 一致
     * @return 绑定到常量的 {@link ConstantCallSite}
     * @throws BootstrapMethodError 若 key 未注册（属于编译器内部错误）
     */
    public static CallSite bootstrap(MethodHandles.Lookup lookup, String name, MethodType type, String key) {
        Object constant = REGISTRY.get(key);
        if (constant == null) {
            throw new BootstrapMethodError("Unregistered script constant key: " + key);
        }
        return new ConstantCallSite(MethodHandles.constant(type.returnType(), constant));
    }

    // ======================== 测试钩子（包可见） ========================

    /** 返回当前注册表条目数，供单元测试断言跨脚本共享行为。 */
    static int registrySizeForTest() {
        return REGISTRY.size();
    }

    /** 清空注册表，供单元测试隔离。 */
    static void clearForTest() {
        REGISTRY.clear();
    }
}
