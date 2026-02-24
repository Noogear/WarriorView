package cn.warriorview.script.core;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.objectweb.asm.MethodVisitor;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 脚本引擎中间表示（IR）体系。
 * <p>
 * 包含全部 IR 节点定义、流程节点类型枚举、处理器接口和节点能力枚举。
 */
@SuppressWarnings("null")
public final class ScriptIR {

    private ScriptIR() {
    }

    // ======================== IR 节点 ========================

    /**
     * 顶层脚本单元。
     */
    public record ScriptUnit(
            String payloadClass,
            int priority,
            ImmutableList<VarDecl> vars,
            ImmutableList<FlowNode> flow) {
        public ScriptUnit withFlow(ImmutableList<FlowNode> newFlow) {
            return new ScriptUnit(payloadClass, priority, vars, newFlow);
        }
    }

    /**
     * 变量声明。
     */
    public record VarDecl(String name, String property, IRType type) {
    }

    /**
     * 通用流程节点，由 {@link FlowNodeType} 枚举标识类型。
     * <p>
     * 性能关键路径使用 {@code numericValue} 和 {@code flags} 字段
     * 存储原生值，避免 attrs Map 的自动装箱。
     */
    public record FlowNode(
            FlowNodeType type,
            ImmutableMap<String, Object> attrs,
            double numericValue,
            int flags) {
        /** 标记：已常量折叠 */
        public static final int FLAG_FOLDED = 1;
        /** 标记：需缓存到局部变量 */
        public static final int FLAG_CACHED = 1 << 1;
        /** 标记：RETURN 后不可达 */
        public static final int FLAG_DEAD_AFTER = 1 << 2;
        /** 标记：变量未被引用（死变量） */
        public static final int FLAG_DEAD_VAR = 1 << 3;
        /** 标记：由优化器自动注入，非用户显式定义 */
        public static final int FLAG_OPTIMIZER_INJECTED = 1 << 4;

        /**
         * 仅 attrs 的简易构造（用于非数值节点）。
         */
        public FlowNode(FlowNodeType type, ImmutableMap<String, Object> attrs) {
            this(type, attrs, 0.0, 0);
        }

        // --- Code Slimming 辅助方法 ---

        /**
         * 获取属性，如果为空则返回提供的默认值。自带泛型推断。
         */
        @SuppressWarnings("unchecked")
        public <T> T getAttrOrDefault(String key, T def) {
            Object val = attrs.get(key);
            return val != null ? (T) val : def;
        }

        /**
         * 获取并转换为指定的枚举类型。
         * 如果不存在或无法转换则抛出明确的编译异常。
         */
        public <E extends Enum<E>> E getEnumAttr(String key, Class<E> enumClass) {
            String val = getAttrOrDefault(key, null);
            if (val == null)
                return null;
            try {
                return Enum.valueOf(enumClass, val.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new ScriptCompileException("Invalid value '" + val + "' for attribute '" + key
                        + "'. Expected one of: " + Arrays.toString(enumClass.getEnumConstants()));
            }
        }

        /**
         * 获取必填属性，如果为空则抛出编译异常。
         */
        @SuppressWarnings("unchecked")
        public <T> T getRequiredAttr(String key) {
            Object val = attrs.get(key);
            if (val == null) {
                throw new ScriptCompileException("Missing required attribute: '" + key + "' in node " + type);
            }
            return (T) val;
        }

        public boolean hasFlag(int flag) {
            return (flags & flag) != 0;
        }

        public FlowNode withFlag(int flag) {
            return new FlowNode(type, attrs, numericValue, flags | flag);
        }

        public FlowNode withNumericValue(double value) {
            return new FlowNode(type, attrs, value, flags);
        }

        public FlowNode withAttr(String key, Object value) {
            return new FlowNode(type, ImmutableMap.<String, Object>builder()
                    .putAll(attrs)
                    .put(key, value)
                    .buildKeepingLast(), numericValue, flags);
        }
    }

    // ======================== 类型枚举 ========================

    /**
     * IR 值类型。
     */
    public enum IRType {
        INT, LONG, DOUBLE, STRING, ENUM, OBJECT, BOOLEAN, COLLECTION;

        private static final java.util.Map<Class<?>, IRType> PRIMITIVE_MAP = java.util.Map.of(
                int.class, INT,
                long.class, LONG,
                double.class, DOUBLE,
                float.class, DOUBLE,
                boolean.class, BOOLEAN);

        /**
         * 从实际的 Java 类中极速推导红外类型 (O(1) Map 路由 + Primitives 解包)。
         */
        public static IRType fromClass(Class<?> rawClass) {
            Class<?> clazz = com.google.common.primitives.Primitives.unwrap(rawClass);
            IRType primitiveType = PRIMITIVE_MAP.get(clazz);
            if (primitiveType != null) {
                return primitiveType;
            }
            if (clazz == String.class)
                return STRING;
            if (clazz.isEnum())
                return ENUM;
            if (java.util.Collection.class.isAssignableFrom(clazz) || clazz.isArray())
                return COLLECTION;
            return OBJECT;
        }

        public boolean isNumeric() {
            return this == INT || this == LONG || this == DOUBLE;
        }

        public boolean isPrimitive() {
            return this == INT || this == LONG || this == DOUBLE || this == BOOLEAN;
        }

        /**
         * 是否为可包含元素的容器类型。
         */
        public boolean isContainer() {
            return this == COLLECTION || this == STRING;
        }
    }

    // ======================== 流程节点类型 ========================

    /**
     * 流程节点类型枚举，每个枚举值关联对应的 {@link FlowNodeHandler} 工厂。
     */
    public enum FlowNodeType {
        CHECK,
        SWITCH,
        RETURN,
        /** 有值返回节点，编译产物为 Function<Object,Object> */
        RETURN_VALUE,
        ACTION;

        private static final EnumMap<FlowNodeType, Supplier<FlowNodeHandler>> FACTORIES = new EnumMap<>(
                FlowNodeType.class);

        public static void registerHandler(FlowNodeType type, Supplier<FlowNodeHandler> factory) {
            FACTORIES.put(type, factory);
        }

        public FlowNodeHandler handler() {
            Supplier<FlowNodeHandler> factory = FACTORIES.get(this);
            if (factory == null) {
                throw new IllegalStateException("No handler registered for FlowNodeType: " + this);
            }
            return factory.get();
        }

        public static FlowNodeType fromYaml(String type) {
            if (type == null)
                return ACTION;
            return switch (type.toLowerCase()) {
                case "check" -> CHECK;
                case "switch" -> SWITCH;
                case "return" -> RETURN;
                case "return_value" -> RETURN_VALUE;
                case "action" -> ACTION;
                default -> throw new IllegalArgumentException("Unknown flow node type: " + type);
            };
        }
    }

    // ======================== 处理器接口 ========================

    /**
     * 流程节点处理器接口，统一解析与字节码发射。
     */
    public interface FlowNodeHandler {
        FlowNode parse(Map<String, Object> yaml);

        void emit(FlowNode node, MethodVisitor mv, CompilationContext ctx);

        EnumSet<NodeCapability> capabilities();
    }

    // ======================== 节点能力 ========================

    public enum NodeCapability {
        HAS_CONDITION,
        HAS_BRANCHES,
        TERMINATES_FLOW,
        SIDE_EFFECT,
        FOLDABLE
    }
}
