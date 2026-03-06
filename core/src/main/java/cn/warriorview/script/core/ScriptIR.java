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
            String id,
            String payloadClass,
            int priority,
            ImmutableList<VarDecl> vars,
            ImmutableList<FlowNode> flow) {
        public ScriptUnit withFlow(ImmutableList<FlowNode> newFlow) {
            return new ScriptUnit(id, payloadClass, priority, vars, newFlow);
        }

        public ScriptUnit withVars(ImmutableList<VarDecl> newVars) {
            return new ScriptUnit(id, payloadClass, priority, newVars, flow);
        }
    }

    /**
     * 变量声明。
     * <p>
     * {@code property} 为特殊哨兵值 {@code "$self"} 时，表示该变量是 payload 的别名，
     * 编译期直接复用 slot 1，不做任何属性提取。
     */
    public record VarDecl(String name, String property, IRType type) {
        /** 是否为 payload 别名（{@code variables: event: $self}）。 */
        public boolean isPayloadAlias() { return "$self".equals(property); }
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

        /**
         * 提取隐式传入的行号上下文。
         *
         * @return 节点所在的 YAML 配置文件中的行号。如果没有，则返回 -1（极好的解耦兼容性）。
         */
        public int getLineNumber() {
            Object line = attrs.get("__line__");
            if (line instanceof Number num) {
                return num.intValue();
            }
            if (line instanceof String str) {
                try {
                    return Integer.parseInt(str);
                } catch (NumberFormatException ignored) {
                }
            }
            return -1;
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
                throw ScriptCompileException.create(this,
                        "Invalid value '" + val + "' for attribute '" + key
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
                throw ScriptCompileException.create(this,
                        "Missing required attribute: '" + key + "' in node " + type);
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

        /** 创建优化器注入的提前终止节点，用于恒假分支截断。 */
        public static FlowNode earlyReturn() {
            return new FlowNode(FlowNodeType.RETURN, ImmutableMap.of())
                    .withFlag(FLAG_DEAD_AFTER | FLAG_OPTIMIZER_INJECTED);
        }

        /** 为属性下沉构建匿名虚拟生产者节点。 */
        public static FlowNode virtualProducer(VarDecl decl) {
            return new FlowNode(FlowNodeType.ACTION,
                    ImmutableMap.of(
                            "_sinking_property", decl.property(),
                            "returnType", decl.type()));
        }
    }

    // ======================== 变量占位符语法 ========================

    /**
     * 模板字符串占位符正则。
     * 支持普通变量 {@code {hp}} 和窄化点链 {@code {entity.name}}。
     */
    private static final Pattern TEMPLATE_PATTERN = Pattern.compile("\\{([\\w.]+)}");

    /**
     * 判断字符串是否为纯单变量引用，如 "{dmg}"（全部内容就是一个占位符，无其他文本）。
     * 注意：点链引用 "{entity.name}" 不属于单变量。
     */
    public static boolean isSingleVar(String s) {
        if (s == null || s.length() <= 2) return false;
        if (s.charAt(0) != '{' || s.charAt(s.length() - 1) != '}') return false;
        if (s.indexOf('{', 1) != -1) return false;
        // 含点号的是点链引用，不是单变量
        String inner = s.substring(1, s.length() - 1);
        return !inner.contains(".");
    }

    /**
     * 判断字符串是否为纯单点链引用，如 "{entity.name}"。
     * <p>
     * 与 {@link #isSingleVar} 互斥：副内容包含 {@code .} 的为点链引用，不包含的为单变量。
     */
    public static boolean isDottedSingleRef(String s) {
        if (s == null || s.length() <= 2) return false;
        if (s.charAt(0) != '{' || s.charAt(s.length() - 1) != '}') return false;
        if (s.indexOf('{', 1) != -1) return false;
        String inner = s.substring(1, s.length() - 1);
        return inner.contains(".");
    }

    /**
     * 判断字符串是否包含模板占位符（如 "HP:{hp} 伤害:{dmg}"）。
     */
    public static boolean isTemplate(String s) {
        return s != null && TEMPLATE_PATTERN.matcher(s).find();
    }

    /**
     * 判断模板 part 是否为窄化点链引用，如 {@code "entity.name"}。
     */
    public static boolean isDottedPart(String part) {
        return part != null && part.contains(".");
    }

    /**
     * 拆分窄化点链引用为 [varName, propertyPath]。
     * 例： {@code "entity.name"} → {@code ["entity", "name"]}。
     */
    public static String[] splitDotted(String part) {
        int dot = part.indexOf('.');
        return new String[]{ part.substring(0, dot), part.substring(dot + 1) };
    }

    /**
     * 从模板字符串中提取所有占位符的基础变量名（点链取头部，去重并保持首次出现顺序）。
     * <p>
     * 例：{@code "HP:{hp} 伤:{entity.dmg} [{hp}]"} → {@code ["hp", "entity"]}
     * <p>
     * 直接复用已编译的 {@link #TEMPLATE_PATTERN}，比调用方自行 {@code Pattern.compile}
     * 性能优一至两个数量级（Pattern.compile 平均耗时约为此方法整体的 10–100x）。
     *
     * @param template 含占位符的字符串
     * @return 基础变量名列表（不含重复项，保持首次出现顺序）
     */
    public static List<String> templateBaseVars(String template) {
        List<String> vars = new ArrayList<>();
        Matcher m = TEMPLATE_PATTERN.matcher(template);
        while (m.find()) {
            String part = m.group(1);
            String base = isDottedPart(part) ? splitDotted(part)[0] : part;
            if (!vars.contains(base)) {    // 模板变量数量通常 ≤ 4，线性扫描优于 Set（无哈希开销，缓存友好）
                vars.add(base);
            }
        }
        return vars;
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
     * 基础枚举核心，用于支持 switch 查表等干净的原始匹配逻辑。
     */
    public enum BaseType {
        INT, LONG, DOUBLE, STRING, ENUM, OBJECT, BOOLEAN, COLLECTION
    }

    /**
     * IR 值类型，携带泛型基因。
     */
    public static final class IRType {
        private final BaseType baseType;
        private final com.google.common.reflect.TypeToken<?> typeToken;

        private IRType(BaseType baseType, com.google.common.reflect.TypeToken<?> typeToken) {
            this.baseType = baseType;
            this.typeToken = typeToken;
        }

        public BaseType base() {
            return baseType;
        }

        public static final IRType INT = new IRType(BaseType.INT,
                com.google.common.reflect.TypeToken.of(Integer.class));
        public static final IRType LONG = new IRType(BaseType.LONG, com.google.common.reflect.TypeToken.of(Long.class));
        public static final IRType DOUBLE = new IRType(BaseType.DOUBLE,
                com.google.common.reflect.TypeToken.of(Double.class));
        public static final IRType STRING = new IRType(BaseType.STRING,
                com.google.common.reflect.TypeToken.of(String.class));
        public static final IRType ENUM = new IRType(BaseType.ENUM, com.google.common.reflect.TypeToken.of(Enum.class));
        public static final IRType OBJECT = new IRType(BaseType.OBJECT,
                com.google.common.reflect.TypeToken.of(Object.class));
        public static final IRType BOOLEAN = new IRType(BaseType.BOOLEAN,
                com.google.common.reflect.TypeToken.of(Boolean.class));
        public static final IRType COLLECTION = new IRType(BaseType.COLLECTION,
                com.google.common.reflect.TypeToken.of(java.util.Collection.class));

        private static final java.util.Map<Class<?>, IRType> PRIMITIVE_MAP = java.util.Map.of(
                int.class, INT,
                long.class, LONG,
                double.class, DOUBLE,
                float.class, DOUBLE,
                boolean.class, BOOLEAN);

        public static IRType fromToken(com.google.common.reflect.TypeToken<?> token) {
            Class<?> clazz = com.google.common.primitives.Primitives.unwrap(token.getRawType());
            IRType primitiveType = PRIMITIVE_MAP.get(clazz);
            if (primitiveType != null) {
                return primitiveType;
            }
            if (clazz == String.class)
                return STRING;
            if (clazz.isEnum())
                return new IRType(BaseType.ENUM, token);
            if (java.util.Collection.class.isAssignableFrom(clazz) || clazz.isArray())
                return new IRType(BaseType.COLLECTION, token);
            return new IRType(BaseType.OBJECT, token);
        }

        public static IRType fromClass(Class<?> rawClass) {
            return fromToken(com.google.common.reflect.TypeToken.of(rawClass));
        }

        public boolean isAssignableFrom(IRType actual) {
            if (this == OBJECT)
                return true;
            if (this.getToken().isSupertypeOf(actual.getToken()))
                return true;
            if (this.equals(actual))
                return true;
            if (this.isNumeric() && actual.isNumeric())
                return true;
            return false;
        }

        public boolean isNumeric() {
            return baseType == BaseType.INT || baseType == BaseType.LONG || baseType == BaseType.DOUBLE;
        }

        public boolean isPrimitive() {
            return baseType == BaseType.INT || baseType == BaseType.LONG || baseType == BaseType.DOUBLE
                    || baseType == BaseType.BOOLEAN;
        }

        public boolean isContainer() {
            return baseType == BaseType.COLLECTION || baseType == BaseType.STRING;
        }

        public com.google.common.reflect.TypeToken<?> getToken() {
            return typeToken;
        }

        public String name() {
            return baseType.name();
        }

        @Override
        public String toString() {
            if (typeToken.getType() instanceof Class) {
                return baseType.name();
            }
            return baseType.name() + "<" + typeToken.toString().replaceAll("\\b[a-z_][a-z0-9_]*\\.", "") + ">";
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (!(o instanceof IRType irType))
                return false;
            return baseType == irType.baseType && typeToken.equals(irType.typeToken);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(baseType, typeToken);
        }
    }

    // ======================== 流程节点类型 ========================

    /**
     * 流程节点类型枚举，每个枚举值关联对应的 {@link FlowNodeHandler} 工厂。
     *
     * <p>每个枚举值可声明 {@code shorthandAlias}：shorthand 值在 attrs 中应被重命名为的目标键
     * （如 {@code check → variable}、{@code math → expr}）；
     * 省略表示保留原键不变。shorthand 触发键始终等于枚举名的小写形式。
     * <p>新增节点类型时只需在此声明即可，{@link cn.warriorview.script.parser.ScriptParser} 无需改动。
     */
    public enum FlowNodeType {
        ACTION,
        RETURN,
        CHECK(  "variable"),
        SWITCH( "variable"),
        ANY,
        ALL,
        MATH(   "expr"    );

        private final String shorthandAlias;

        FlowNodeType() { this.shorthandAlias = null; }
        FlowNodeType(String shorthandAlias) { this.shorthandAlias = shorthandAlias; }

        /** YAML 短语法触发字段名，始终等于枚举名的小写形式。 */
        public String shorthandKey() { return name().toLowerCase(); }

        /** shorthand 值在 attrs 中应被重命名为的目标键，{@code null} 表示不需要重命名。 */
        public String shorthandAlias() { return shorthandAlias; }

        /** shorthand key → FlowNodeType 静态查找表，由枚举初始化时自动构建。 */
        private static final java.util.Map<String, FlowNodeType> SHORTHAND_MAP;
        static {
            java.util.Map<String, FlowNodeType> m = new java.util.LinkedHashMap<>();
            for (FlowNodeType t : values()) m.put(t.shorthandKey(), t);
            SHORTHAND_MAP = java.util.Collections.unmodifiableMap(m);
        }

        /**
         * 按 shorthand key 查找节点类型，未命中返回 {@code null}。
         * 供 {@link cn.warriorview.script.parser.ScriptParser} 泛型分发使用。
         */
        public static FlowNodeType fromShorthand(String key) {
            return SHORTHAND_MAP.get(key);
        }

        /** 保留的 shorthand key 集合，用于动态 Action 推断时过滤。 */
        public static java.util.Set<String> reservedKeys() {
            return SHORTHAND_MAP.keySet();
        }

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
            try {
                return valueOf(type.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw ScriptCompileException.parse("Unknown flow node type: " + type);
            }
        }
    }

    // ======================== 处理器接口 ========================

    /**
     * 流程节点处理器接口，统一解析与字节码发射。
     */
    public interface FlowNodeHandler {
        FlowNode parse(ParseContext ctx);

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

        default java.util.List<String> getAllConsumedVariables(FlowNode node) {
            String single = getConsumedVariable(node);
            return single != null ? java.util.List.of(single) : java.util.List.of();
        }

        default FlowNode inlineAction(FlowNode node, FlowNode inlineHook) {
            return node.withoutAttr("variable").withAttr("conditionAction", inlineHook);
        }
    }

    /**
     * 允许节点自行校验参数与上下文变量之间的类型兼容性。
     */
    public interface TypeValidator {
        void validateTypes(FlowNode node, cn.warriorview.script.core.CompilationContext ctx);
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
     * 允许流节点汇报自身是对某个变量值的产出者，并提供剥离产出标记的能力。
     */
    public interface VariableProducer {
        String getProducedVariable(FlowNode node);

        /** 剥离节点中的“产出变量”标记，返回纯执行节点。由具体 handler 处理自己的 attr 布局。 */
        FlowNode stripProducedVariable(FlowNode node);
        /**
         * 若该节点产出一个编译期已知的常量值，返回该值；否则返回 {@code null}。
         * <p>
         * 用于 {@link cn.warriorview.script.optimizer.ScriptOptimizer} 值域传播：
         * 常量 MATH 产出可直接注入后续 CHECK 的约束，使其折叠为恒真/恒假。
         */
        default Object getProducedConstantValue(FlowNode node) { return null; }    }
}
