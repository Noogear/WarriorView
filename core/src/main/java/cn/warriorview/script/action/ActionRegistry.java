package cn.warriorview.script.action;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 动作注册表，管理脚本可调用的动作定义。
 * <p>
 * 通过 {@link ScriptAction} 注解标注静态方法，调用 {@link #scanAndRegister(Class[])}
 * 自动扫描注册，无需手动硬编码。
 */
@SuppressWarnings("null")
public final class ActionRegistry {

    // ======================== @ScriptAction 注解 ========================

    /**
     * 标注一个静态方法为脚本动作。
     * <p>
     * 方法必须为 {@code public static}，注册表将从方法签名自动推导
     * ASM 调用描述符和参数数量。
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    public @interface ScriptAction {
        /**
         * 动作名称（YAML 中使用的标识符）。
         */
        String value();

        /**
         * 是否将方法第一个参数视为脚本 payload 对象（由引擎自动注入，不需要在 YAML args 中提供）。
         * <p>
         * 默认 {@code true}：第一个参数是 payload，YAML {@code args} 从第二个参数开始对应。<br>
         * 设为 {@code false}：纯工具方法，所有参数均由 YAML {@code args} 提供，引擎不注入 payload。
         * <pre>{@code
         * // consumesPayload = true（默认）：第一位是 payload，YAML 只需传后续参数
         * @ScriptAction("sendMessage")
         * public static void sendMessage(Player target, String message) { ... }
         * // YAML: args: ["Hello"]
         *
         * // consumesPayload = false：纯工具函数，所有参数来自 YAML
         * @ScriptAction(value = "formatNumber", consumesPayload = false)
         * public static String formatNumber(int value, int digits) { ... }
         * // YAML: args: ["{hp}", "2"]
         * }</pre>
         */
        boolean consumesPayload() default true;
    }

    // ======================== ActionDef ========================

    /**
     * 动作定义，存储目标方法的字节码调用信息。
     */
    public record ActionDef(
            String owner,
            String method,
            String descriptor,
            int invokeType,
            int paramCount,
            Class<?>[] paramTypes,
            com.google.common.reflect.TypeToken<?>[] genericParamTypes,
            Class<?> returnType,
            boolean isBuiltin,
            boolean consumesPayload) {

        /**
         * 简易构造（非 builtin，consumesPayload=true）。
         */
        public ActionDef(String owner, String method, String descriptor, int invokeType, int paramCount,
                Class<?>[] paramTypes, com.google.common.reflect.TypeToken<?>[] genericParamTypes,
                Class<?> returnType) {
            this(owner, method, descriptor, invokeType, paramCount, paramTypes, genericParamTypes, returnType, false, true);
        }

        /**
         * 简易构造（指定 isBuiltin，consumesPayload=true）。
         */
        public ActionDef(String owner, String method, String descriptor, int invokeType, int paramCount,
                Class<?>[] paramTypes, com.google.common.reflect.TypeToken<?>[] genericParamTypes,
                Class<?> returnType, boolean isBuiltin) {
            this(owner, method, descriptor, invokeType, paramCount, paramTypes, genericParamTypes, returnType, isBuiltin, true);
        }
    }

    private final Map<String, ActionDef> actions = new HashMap<>();

    /**
     * 系统保留字（实例级，可动态扩展）。
     * 禁止将这些字符串作为动作名称，以防与 ScriptParser 动态推断规则发生碰撞。
     */
    private final Set<String> reservedKeys = new java.util.HashSet<>(Set.of(
            "type", "action", "return", "check", "switch", "args", "store", "priority", "event"));

    public ActionRegistry() {
    }

    /**
     * 动态添加一个保留字。已有的会被静默忽略（幂等）。
     *
     * @param key 需要保留的关键字（大小写不敏感，内部统一转小写存储）
     */
    public void addReservedKey(String key) {
        Preconditions.checkNotNull(key, "reserved key");
        reservedKeys.add(key.toLowerCase());
    }

    /**
     * 批量添加保留字。
     *
     * @param keys 需要添加的关键字集合
     */
    public void addReservedKeys(java.util.Collection<String> keys) {
        Preconditions.checkNotNull(keys, "reserved keys");
        keys.forEach(k -> reservedKeys.add(k.toLowerCase()));
    }

    /**
     * 返回当前保留字的不可变快照（用于调试/展示）。
     */
    public Set<String> reservedKeys() {
        return java.util.Collections.unmodifiableSet(reservedKeys);
    }

    /**
     * 扫描指定类中带 {@link ScriptAction} 注解的静态方法并注册。
     * <p>
     * 自动从方法签名推导 ASM owner/descriptor/invokeType/paramCount。
     */
    public void scanAndRegister(Class<?>... providerClasses) {
        for (Class<?> clazz : providerClasses) {
            String owner = Type.getInternalName(clazz);
            for (Method method : clazz.getDeclaredMethods()) {
                ScriptAction annotation = method.getAnnotation(ScriptAction.class);
                if (annotation == null)
                    continue;

                Preconditions.checkArgument(
                        Modifier.isPublic(method.getModifiers()) && Modifier.isStatic(method.getModifiers()),
                        "@ScriptAction method must be public static: %s", method);

                String actionName = annotation.value();
                String descriptor = Type.getMethodDescriptor(method);
                int paramCount = method.getParameterCount();
                Class<?>[] paramTypes = method.getParameterTypes();
                java.lang.reflect.Type[] genericTypes = method.getGenericParameterTypes();
                com.google.common.reflect.TypeToken<?>[] genericParamTypes = new com.google.common.reflect.TypeToken<?>[paramCount];
                for (int i = 0; i < paramCount; i++) {
                    genericParamTypes[i] = com.google.common.reflect.TypeToken.of(genericTypes[i]);
                }
                Class<?> returnType = method.getReturnType();

                register(actionName, new ActionDef(
                        owner, method.getName(), descriptor,
                        Opcodes.INVOKESTATIC, paramCount, paramTypes, genericParamTypes, returnType, true,
                        annotation.consumesPayload()));
            }
        }
    }

    /**
     * 手动注册动作。
     */
    public void register(String name, ActionDef def) {
        Preconditions.checkNotNull(name, "action name");
        Preconditions.checkArgument(!reservedKeys.contains(name.toLowerCase()),
                "Cannot register action using reserved keyword: %s", name);
        Preconditions.checkNotNull(def, "action definition");
        actions.put(name, def);
    }

    /**
     * 查找动作定义。
     */
    public ActionDef lookup(String name) {
        ActionDef def = actions.get(name);
        if (def == null) {
            throw new IllegalArgumentException("Unknown action: " + name
                    + ". Available: " + actions.keySet());
        }
        return def;
    }

    /**
     * 检查动作是否已注册。
     */
    public boolean has(String name) {
        return actions.containsKey(name);
    }

    /**
     * 返回所有已注册动作的不可变快照。
     */
    public ImmutableMap<String, ActionDef> all() {
        return ImmutableMap.copyOf(actions);
    }
}
