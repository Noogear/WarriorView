package cn.warriorview.listener;

import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.objectweb.asm.Type;

/**
 * 属性路径解析器。
 * 将点分路径（如 {@code "killer.name"}）解析为有序的
 * {@link cn.warriorview.listener.AccessorPathResolver.PathStep} 链，
 * 推断最终类型。解析结果全局缓存，PGO 重编译时零反射开销。
 */
public final class AccessorPathResolver {

    private AccessorPathResolver() {
    }

    /** 全局解析缓存 */
    private static final ConcurrentHashMap<String, ResolveResult> CACHE = new ConcurrentHashMap<>();

    /**
     * 解析给定事件类上的属性路径，结果全局缓存。
     *
     * @param eventClass 事件根类型
     * @param dotPath    点分属性路径，例如 {@code "entity.killer.name"}
     * @return 解析结果
     */
    public static ResolveResult resolve(Class<?> eventClass, String dotPath) {
        Objects.requireNonNull(eventClass, "eventClass 不能为 null");
        Objects.requireNonNull(dotPath, "dotPath 不能为 null");

        String cacheKey = eventClass.getName() + "#" + dotPath;
        return CACHE.computeIfAbsent(cacheKey, k -> resolveUncached(eventClass, dotPath));
    }

    /** 清除解析缓存（热重载用） */
    public static void clearCache() {
        CACHE.clear();
    }

    private static ResolveResult resolveUncached(Class<?> eventClass, String dotPath) {
        List<PathStep> steps = new ArrayList<>();
        Class<?> current = eventClass;

        for (String part : dotPath.split("\\.")) {
            Member member = findAccessor(current, part);
            PathStep step = new PathStep(current, member);
            steps.add(step);
            current = step.returnType();
        }

        return new ResolveResult(List.copyOf(steps), current);
    }

    /**
     * 查找属性访问器。
     * 策略：公共字段 → {@link java.beans.Introspector} JavaBeans 规范（自动处理 get/is 前缀）→ 同名方法。
     */
    private static Member findAccessor(Class<?> clazz, String name) {
        // 1. 公共字段
        try {
            return clazz.getField(name);
        } catch (NoSuchFieldException ignored) {
        }

        // 2. JavaBeans 规范（Introspector 自动缓存 BeanInfo，覆盖 getXxx / isXxx）
        try {
            for (PropertyDescriptor pd : Introspector.getBeanInfo(clazz).getPropertyDescriptors()) {
                if (pd.getName().equals(name) && pd.getReadMethod() != null) {
                    return pd.getReadMethod();
                }
            }
        } catch (IntrospectionException ignored) {
        }

        // 3. 回退：同名无参方法（如 Bukkit 的 damage()、killer() 等无前缀方法）
        try {
            return clazz.getMethod(name);
        } catch (NoSuchMethodException ignored) {
        }

        throw new ResolveException(
                "无法在 " + clazz.getName() + " 中解析属性 '" + name + "'。" +
                        "请检查属性名是否正确，或该属性是否有公共 getter 方法。");
    }

    // ───────── 结果数据类 ─────────

    /** 路径解析结果 */
    public record ResolveResult(List<PathStep> steps, Class<?> finalType) {
    }

    /** 路径中的单步访问信息 */
    public record PathStep(
            Class<?> owner,
            String name,
            String descriptor,
            Class<?> returnType,
            boolean isField) {
        PathStep(Class<?> owner, Member member) {
            this(
                    owner,
                    member.getName(),
                    member instanceof Field f
                            ? Type.getDescriptor(f.getType())
                            : Type.getMethodDescriptor((Method) member),
                    member instanceof Field f
                            ? f.getType()
                            : ((Method) member).getReturnType(),
                    member instanceof Field);
        }
    }

    /** 路径解析异常 */
    public static class ResolveException extends RuntimeException {
        public ResolveException(String message) {
            super(message);
        }

        public ResolveException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
