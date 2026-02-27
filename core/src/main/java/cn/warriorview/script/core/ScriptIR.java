package cn.warriorview.script.core;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.objectweb.asm.MethodVisitor;
import cn.warriorview.script.optimizer.ScriptOptimizer;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.ArrayList;

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

        public ScriptUnit withVars(ImmutableList<VarDecl> newVars) {
            return new ScriptUnit(payloadClass, priority, newVars, flow);
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

        public FlowNode withoutAttr(String key) {
            if (!attrs.containsKey(key)) {
                return this;
            }
            ImmutableMap.Builder<String, Object> builder = ImmutableMap.builder();
            for (Map.Entry<String, Object> entry : attrs.entrySet()) {
                if (!entry.getKey().equals(key)) {
                    builder.put(entry);
                }
            }
            return new FlowNode(type, builder.build(), numericValue, flags);
        }
    }

    // ======================== 变量占位符语法 ========================

    /** 模板字符串占位符正则 */
    private static final Pattern TEMPLATE_PATTERN = Pattern.compile("\\{(\\w+)}");

    /**
     * 判断字符串是否为纯单变量引用，如 "{dmg}"（全部内容就是一个占位符，无其他文本）。
     */
    public static boolean isSingleVar(String s) {
        return s != null && s.length() > 2 && s.charAt(0) == '{' && s.charAt(s.length() - 1) == '}'
                && s.indexOf('{', 1) == -1;
    }

    /**
     * 判断字符串是否包含模板占位符（如 "HP:{hp} 伤害:{dmg}"）。
     */
    public static boolean isTemplate(String s) {
        return s != null && TEMPLATE_PATTERN.matcher(s).find();
    }

    /**
     * 解析模板字符串，提取交替的字面量和变量名列表。
     * 例如 "HP:{hp}!" → ["HP:", "hp", "!"]
     */
    public static List<String> parseTemplate(String template) {
        List<String> parts = new ArrayList<>();
        Matcher matcher = TEMPLATE_PATTERN.matcher(template);
        int last = 0;
        while (matcher.find()) {
            if (matcher.start() > last) {
                parts.add(template.substring(last, matcher.start()));
            }
            parts.add(matcher.group(1));
            last = matcher.end();
        }
        if (last < template.length()) {
            parts.add(template.substring(last));
        }
        return parts;
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
        ACTION,
        ANY,
        ALL;

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
                case "action" -> ACTION;
                case "any" -> ANY;
                case "all" -> ALL;
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

    /**
     * 实现该接口的处理器表示其是一个条件判断原语，能够向外统一提供条件比较的底层逻辑方法。
     * 允许复合节点（如 ANY/ALL）多态调用以判定任何条件，而不必强制下转为 CheckNodeHandler。
     */
    public interface ConditionEmitter {
        /**
         * 发射单个条件的比较字节码。
         * 返回该条件成立时控制流应当执行的 Opcodes 跳转指令（例如 Opcodes.IFEQ）。
         */
        int emitCondition(FlowNode node, MethodVisitor mv, CompilationContext ctx);
    }

    /**
     * 允许内部节点暴露自己所包含的所有逻辑上的子流程节点（如条件块产生的子集、any块的 children），
     * 供 ScriptOptimizer 进行生命周期遍历而无需猜想具体变量。
     */
    public interface NodeTraverser {
        Iterable<FlowNode> traverseChildren(FlowNode node);
    }

    /**
     * 允许节点在编译前自身提取编译期常量，代替优化器寻找。
     * 结果需追加至 defs，提取完成后可通过 `withAttr` 返回带标记的新节点以备字节码内消洗。
     */
    public interface ConstantHoister {
        FlowNode hoistConstants(FlowNode node, List<CompilationContext.ConstantDef> defs, int[] counter);
    }

    // ======================== 节点能力 ========================

    public enum NodeCapability {
        HAS_CONDITION,
        HAS_BRANCHES,
        TERMINATES_FLOW,
        SIDE_EFFECT,
        FOLDABLE
    }

    // ======================== 基于多态的脱离分析约束 ========================

    /**
     * 允许流节点自行判定在没有额外环境约束时能否得出绝对真伪（常量折叠）。
     */
    public interface ConstantFolder {
        Boolean evaluateFold(FlowNode node, CompilationContext ctx);
    }

    /**
     * 允许流节点报告其检查的变量名，并在已有约束下尝试被折叠，或对现有约束进行更新。
     */
    public interface RangePropagator {
        default String getConstrainedVariable(FlowNode node) {
            return node.getAttrOrDefault("variable", null);
        }

        Boolean tryFoldWithRange(FlowNode node, ScriptOptimizer.ValueRange range);

        ScriptOptimizer.ValueRange updateRange(FlowNode node, ScriptOptimizer.ValueRange range);
    }

    /**
     * 允许流节点在其结构中报告读取的特征变量，并提供吸收 Action 的虚拟闭包替换支持（用于按需下沉属性读取）。
     */
    public interface VariableConsumer {
        default String getConsumedVariable(FlowNode node) {
            return node.getAttrOrDefault("variable", null);
        }

        default FlowNode inlineAction(FlowNode node, FlowNode inlineHook) {
            return node.withoutAttr("variable").withAttr("conditionAction", inlineHook);
        }
    }

    /**
     * 允许流节点根据上下文的权重表对内部分支进行重新排列重组，以提升短路命中率。
     */
    public interface BranchReorderer {
        FlowNode reorderBranches(FlowNode node, cn.warriorview.script.core.CompilationContext ctx);
    }

    /**
     * 允许对树形流节点的子级迭代执行映射回调并安全重建节点（主要用于静态常量提升阶段修剪树干）。
     */
    public interface NodeMutator extends NodeTraverser {
        FlowNode mapChildren(FlowNode node, java.util.function.Function<FlowNode, FlowNode> mapper);
    }

    /**
     * 允许流节点汇报自身是对某个变量值的产出者，并提供为按需消费环境的快照构建读取闭包的能力。
     */
    public interface VariableProducer {
        String getProducedVariable(FlowNode node);

        FlowNode createVirtualProducer(VarDecl decl);
    }
}
