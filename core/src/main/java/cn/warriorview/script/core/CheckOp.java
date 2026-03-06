package cn.warriorview.script.core;

import cn.warriorview.script.core.ScriptIR.BaseType;
import cn.warriorview.script.core.ScriptIR.IRType;
import org.objectweb.asm.Opcodes;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 脚本 CHECK 节点支持的全部操作符枚举。
 * <p>
 * 将原先散落在 {@code CheckNodeHandler}、{@code ScriptBuilder}、{@code ScriptOptimizer}
 * 等多处地方的字符串魔法值、类型兼容性白名单、跳转指令映射等逻辑统一收敛至此。
 * <p>
 * 所有操作符前均可加 {@code !} 前缀取反（由 {@link #resolve(String)} 处理）。
 */
public enum CheckOp {

    // ──────── 空值/类型检测 ────────
    NULL("null", Category.NULL_CHECK, EnumSet.of(BaseType.OBJECT, BaseType.STRING, BaseType.COLLECTION, BaseType.ENUM)),

    INSTANCEOF("instanceof", Category.TYPE_CHECK, EnumSet.of(BaseType.OBJECT, BaseType.STRING, BaseType.COLLECTION, BaseType.ENUM)),

    // ──────── 相等 ────────
    EQ("==", Category.EQUALITY, EnumSet.of(BaseType.INT, BaseType.LONG, BaseType.DOUBLE, BaseType.STRING, BaseType.BOOLEAN, BaseType.OBJECT, BaseType.ENUM)),

    NEQ("!=", Category.EQUALITY, EnumSet.of(BaseType.INT, BaseType.LONG, BaseType.DOUBLE, BaseType.STRING, BaseType.BOOLEAN, BaseType.OBJECT, BaseType.ENUM)),

    // ──────── 数值比较 ────────
    GT(">", Category.NUMERIC, EnumSet.of(BaseType.INT, BaseType.LONG, BaseType.DOUBLE)),
    GTE(">=", Category.NUMERIC, EnumSet.of(BaseType.INT, BaseType.LONG, BaseType.DOUBLE)),
    LT("<", Category.NUMERIC, EnumSet.of(BaseType.INT, BaseType.LONG, BaseType.DOUBLE)),
    LTE("<=", Category.NUMERIC, EnumSet.of(BaseType.INT, BaseType.LONG, BaseType.DOUBLE)),

    // ──────── 字符串专属 ────────
    STARTS_WITH("starts_with", Category.STRING_OP, EnumSet.of(BaseType.STRING)),
    ENDS_WITH("ends_with", Category.STRING_OP, EnumSet.of(BaseType.STRING)),
    MATCHES("matches", Category.STRING_OP, EnumSet.of(BaseType.STRING)),

    // ──────── 包含/成员 ────────
    CONTAINS("contains", Category.MEMBERSHIP, EnumSet.of(BaseType.STRING, BaseType.COLLECTION)),
    IN("in", Category.MEMBERSHIP, EnumSet.of(BaseType.STRING, BaseType.INT, BaseType.ENUM)),

    // ──────── 范围 ────────
    BETWEEN("between", Category.NUMERIC, EnumSet.of(BaseType.INT, BaseType.LONG, BaseType.DOUBLE));

    // ======================== 字段 ========================

    private final String symbol;
    private final Category category;
    private final EnumSet<BaseType> supportedTypes;

    CheckOp(String symbol, Category category, EnumSet<BaseType> supportedTypes) {
        this.symbol = symbol;
        this.category = category;
        this.supportedTypes = supportedTypes;
    }

    // ======================== 分类枚举 ========================

    /**
     * 操作符分类——用于分组行为特性（数值/字符串/空值/成员判定等）。
     */
    public enum Category {
        NULL_CHECK, TYPE_CHECK, EQUALITY, NUMERIC, STRING_OP, MEMBERSHIP
    }

    // ======================== 解析 ========================

    /**
     * 从原始字符串解析操作符（支持 {@code !} 前缀取反）。
     * <p>
     * 解析流程：1) 规范化多重 {@code !} 前缀 → 2) 查表匹配 → 3) 尝试拼写建议 → 4) 报错。
     *
     * @param rawOp 原始操作符字符串（如 "==", "!null", "!!contains"）
     * @return 解析结果，包含枚举值和是否取反
     * @throws ScriptCompileException 如果操作符不合法
     */
    public static Resolved resolve(String rawOp) {
        if (rawOp == null || rawOp.isEmpty()) {
            throw ScriptCompileException.parse("Operator cannot be null or empty.");
        }

        // 优先精确匹配（处理 "!=" 等自带 ! 的 symbol）
        CheckOp exact = SYMBOL_MAP.get(rawOp);
        if (exact != null) {
            return new Resolved(exact, false);
        }

        // 规范化多重 ! 前缀
        int bangCount = 0;
        while (bangCount < rawOp.length() && rawOp.charAt(bangCount) == '!') bangCount++;
        boolean negate = (bangCount % 2) == 1;
        String bare = rawOp.substring(bangCount);

        // 精确查表
        CheckOp found = SYMBOL_MAP.get(bare);
        if (found != null) {
            return new Resolved(found, negate);
        }

        // 拼写建议
        String suggestion = TYPO_MAP.get(bare);
        if (suggestion != null) {
            String suggestedFull = negate ? "!" + suggestion : suggestion;
            throw ScriptCompileException.parse(
                    String.format("Unknown operator '%s'. Did you mean '%s'?", rawOp, suggestedFull));
        }

        throw ScriptCompileException.parse(
                String.format("Unknown operator '%s'. Supported operators: %s (all support '!' prefix negation).",
                        rawOp, ALL_SYMBOLS));
    }

    /**
     * 仅按 symbol 查表，不做取反解析也不抛异常。
     *
     * @return 对应枚举值，不存在时 {@code null}
     */
    public static CheckOp fromSymbol(String symbol) {
        return SYMBOL_MAP.get(symbol);
    }

    // ======================== 解析结果 ========================

    /**
     * 操作符解析结果，包含枚举实例和取反标志。
     * <p>
     * 替代原来的 {@code OpInfo(String op, boolean negate)} record。
     */
    public record Resolved(CheckOp op, boolean negate) {

        /** 取反后的 symbol（便于日志/错误消息）。 */
        public String toSymbol() {
            return negate ? "!" + op.symbol : op.symbol;
        }
    }

    // ======================== 类型兼容性 ========================

    /**
     * 检查该操作符是否支持给定 IRType。通过 BaseType 进行比较，避免 TypeToken 实例不等问题。
     */
    public boolean supportsType(IRType type) {
        return supportedTypes.contains(type.base());
    }

    /**
     * AOT 类型兼容性验证。不兼容时抛出编译异常并附带提示。
     *
     * @param variable 变量名（用于错误消息）
     * @param type     变量的 IRType
     * @throws ScriptCompileException 类型不兼容时
     */
    public void validateType(String variable, IRType type) {
        if (supportsType(type)) {
            return;
        }

        // 特例：== 用于 COLLECTION 虽然不在 supportedTypes 中，但给出更精确的提示
        if (this == EQ && type.base() == BaseType.COLLECTION) {
            throw ScriptCompileException.type(null,
                    String.format(
                            "Operator '==' on COLLECTION variable '%s' compares by reference, which is almost certainly not what you want. "
                                    + "Hint: did you mean 'contains' to check membership?",
                            variable));
        }

        String hint = buildTypeHint(type);
        throw ScriptCompileException.type(null,
                String.format("Operator '%s' requires %s, but variable '%s' is of type %s. %s",
                        symbol, describeExpectedTypes(), variable, type, hint));
    }

    // ======================== 跳转指令映射 ========================

    /**
     * DCMPG/LCMP 后的单值比较跳转 opcode（double/long 共用）。
     *
     * @throws IllegalArgumentException 当操作符不是数值比较运算符时
     */
    public int cmpJump() {
        return switch (this) {
            case GT  -> Opcodes.IFGT;
            case GTE -> Opcodes.IFGE;
            case LT  -> Opcodes.IFLT;
            case LTE -> Opcodes.IFLE;
            case EQ  -> Opcodes.IFEQ;
            default  -> throw new IllegalArgumentException("Unsupported comparison op: " + symbol);
        };
    }

    /**
     * 双栈值 int 比较跳转 opcode。
     *
     * @throws IllegalArgumentException 当操作符不是数值比较运算符时
     */
    public int intCmpJump() {
        return switch (this) {
            case GT  -> Opcodes.IF_ICMPGT;
            case GTE -> Opcodes.IF_ICMPGE;
            case LT  -> Opcodes.IF_ICMPLT;
            case LTE -> Opcodes.IF_ICMPLE;
            case EQ  -> Opcodes.IF_ICMPEQ;
            default  -> throw new IllegalArgumentException("Unsupported comparison op for int: " + symbol);
        };
    }

    // ======================== 分类查询 ========================

    /** 操作符是否为数值类比较（决定 value 字段是否可尝试作为数学表达式解析）。 */
    public boolean isNumeric() {
        return category == Category.NUMERIC || this == EQ;
    }

    /**
     * 该操作符是否支持 {@code ValueRange} 值域折叠。
     * <p>
     * GT/GTE/LT/LTE/EQ 均可与 [min, max] 约束做代数分析；BETWEEN 语义不同，不在此列。
     */
    public boolean isRangeFoldable() {
        return this == GT || this == GTE || this == LT || this == LTE || this == EQ;
    }

    /**
     * 对于将变量引用压栈后的 null 检查操作符，返回“条件满足 → 跳转到 continue”的 JVM 跳转指令。
     * <p>
     * {@code NULL} → {@code IFNONNULL}:变量为非 null 时跳过 fail 代码（即检查变量 non-null 成功）。
     *
     * @throws IllegalArgumentException 若操作符不适用 null 检查语义
     */
    public int nullJumpInsn() {
        if (this == NULL) return Opcodes.IFNONNULL;
        throw new IllegalArgumentException("nullJumpInsn not applicable for op: " + symbol);
    }

    /**
     * 对应操作符的 Java {@link String} 实例方法名（仅适用于 STRING_OP 和 contains）。
     * <p>
     * 用于 {@code INVOKEVIRTUAL java/lang/String.<methodName>} 指令生成，消除硬编码字符串。
     *
     * @throws IllegalStateException 若操作符无对应 String 方法名
     */
    public String javaStringMethodName() {
        return switch (this) {
            case STARTS_WITH -> "startsWith";
            case ENDS_WITH   -> "endsWith";
            case MATCHES     -> "matches";
            case CONTAINS    -> "contains";
            default -> throw new IllegalStateException("No Java String method name for op: " + symbol);
        };
    }

    /**
     * 在栈顶已有 {@code .equals()} 返回的 int（0 或 1）时，条件"成立"对应的 JVM 跳转指令。
     * <ul>
     *   <li>{@code EQ}  → {@code IFNE}（equals=true=1，非零跳转）</li>
     *   <li>{@code NEQ} → {@code IFEQ}（equals=false=0，零跳转）</li>
     * </ul>
     *
     * @throws IllegalArgumentException 若操作符不适用对象等值语义
     */
    public int afterEqualsJump() {
        return switch (this) {
            case EQ  -> Opcodes.IFNE;
            case NEQ -> Opcodes.IFEQ;
            default  -> throw new IllegalArgumentException("afterEqualsJump not applicable for op: " + symbol);
        };
    }

    /** 操作符分类。 */
    public Category category() {
        return category;
    }

    /** 操作符 YAML 符号字符串（如 "==", "contains"）。 */
    public String symbol() {
        return symbol;
    }

    /** 该操作符支持的所有 BaseType 集合（不可变视图）。 */
    public Set<BaseType> supportedTypes() {
        return java.util.Collections.unmodifiableSet(supportedTypes);
    }

    // ======================== 常量折叠支持 ========================

    /**
     * 编译期常量折叠：对两个数值执行比较。
     *
     * @return 比较结果，或 {@code null} 表示该操作符不支持数值折叠
     */
    public Boolean foldNumeric(double varValue, double cmpValue) {
        return switch (this) {
            case GT  -> varValue > cmpValue;
            case GTE -> varValue >= cmpValue;
            case LT  -> varValue < cmpValue;
            case LTE -> varValue <= cmpValue;
            case EQ  -> varValue == cmpValue;
            default  -> null;
        };
    }

    /**
     * 编译期对象/字符串常量折叠：对两个非数值对象执行语义比较。
     * <p>
     * 调用方应在 varValue 和 cmpValue 均为数值时先走 {@link #foldNumeric}；<br>
     * 此方法处理字符串、布尔等非数值类型的折叠。
     *
     * @return 折叠结果，或 {@code null} 表示无法在编译期确定
     */
    public Boolean foldObject(Object varValue, Object cmpValue) {
        if (varValue == null || cmpValue == null) return null;
        return switch (this) {
            case EQ  -> varValue.equals(cmpValue);
            case NEQ -> !varValue.equals(cmpValue);
            case CONTAINS   -> varValue instanceof String s && cmpValue instanceof String sub ? s.contains(sub) : null;
            case STARTS_WITH -> varValue instanceof String s && cmpValue instanceof String sub ? s.startsWith(sub) : null;
            case ENDS_WITH   -> varValue instanceof String s && cmpValue instanceof String sub ? s.endsWith(sub) : null;
            default -> null;
        };
    }

    /**
     * 值域范围折叠: 当前约束 [min, max] 下，比较操作是否恒真/恒假。
     *
     * @return Boolean.TRUE=恒真, Boolean.FALSE=恒假, null=不确定
     */
    public Boolean foldRange(double min, double max, double cmpValue, Object exactValue) {
        return switch (this) {
            case GT  -> min > cmpValue ? Boolean.TRUE : max <= cmpValue ? Boolean.FALSE : null;
            case GTE -> min >= cmpValue ? Boolean.TRUE : max < cmpValue ? Boolean.FALSE : null;
            case LT  -> max < cmpValue ? Boolean.TRUE : min >= cmpValue ? Boolean.FALSE : null;
            case LTE -> max <= cmpValue ? Boolean.TRUE : min > cmpValue ? Boolean.FALSE : null;
            case EQ  -> {
                if (exactValue != null) {
                    yield exactValue.equals(cmpValue)
                            || (exactValue instanceof Number n && n.doubleValue() == cmpValue)
                            ? Boolean.TRUE : Boolean.FALSE;
                }
                yield min == max && min == cmpValue ? Boolean.TRUE : null;
            }
            default -> null;
        };
    }

    // ======================== 常量提升 ========================

    /**
     * {@code in} 操作符的列表展开阈值：列表项数 &le; 此值时展开为多路比较，否则提升为 {@code static final Set}。
     */
    public static final int IN_SET_THRESHOLD = 3;

    /**
     * 描述节点在编译期需要提升为 {@code static final} 的常量类型。
     */
    public enum HoistKind {
        /** 无需提升。 */
        NONE,
        /** {@code matches} → {@code java.util.regex.Pattern} 预编译字段。 */
        PATTERN,
        /** {@code in} 列表超阈值 → {@code Set} 常量字段。 */
        IN_SET,
        /** {@code between} → {@code double[]} 范围数组字段。 */
        RANGE_ARRAY
    }

    /**
     * 该操作符的编译期常量提升策略。
     */
    public HoistKind hoistKind() {
        return switch (this) {
            case MATCHES -> HoistKind.PATTERN;
            case IN      -> HoistKind.IN_SET;
            case BETWEEN -> HoistKind.RANGE_ARRAY;
            default      -> HoistKind.NONE;
        };
    }

    /**
     * 提升字段名前缀（仅 {@link #hoistKind()} != {@code NONE} 时有意义）。
     * <p>
     * 格式："PATTERN_", "SET_", "RANGE_"。
     *
     * @throws IllegalStateException 若操作符无需提升
     */
    public String hoistFieldPrefix() {
        return switch (this) {
            case MATCHES -> "PATTERN_";
            case IN      -> "SET_";
            case BETWEEN -> "RANGE_";
            default      -> throw new IllegalStateException("No hoist field prefix for op: " + symbol);
        };
    }

    // ======================== 内部常量 ========================

    /** symbol → CheckOp 快速查找表。 */
    private static final Map<String, CheckOp> SYMBOL_MAP;

    /** 所有合法 symbol 的逗号分隔字符串（用于错误消息）。 */
    static final String ALL_SYMBOLS;

    /** 常见拼写错误 → 正确 symbol 的映射。 */
    private static final Map<String, String> TYPO_MAP = Map.ofEntries(
            Map.entry("startswith", "starts_with"),
            Map.entry("startsWith", "starts_with"),
            Map.entry("start_with", "starts_with"),
            Map.entry("endswith", "ends_with"),
            Map.entry("endsWith", "ends_with"),
            Map.entry("end_with", "ends_with"),
            Map.entry("match", "matches"),
            Map.entry("regex", "matches"),
            Map.entry("include", "contains"),
            Map.entry("includes", "contains"),
            Map.entry("has", "contains"),
            Map.entry("eq", "=="),
            Map.entry("ne", "!="),
            Map.entry("neq", "!="),
            Map.entry("gt", ">"),
            Map.entry("lt", "<"),
            Map.entry("gte", ">="),
            Map.entry("lte", "<="),
            Map.entry("is", "=="),
            Map.entry("not", "!="),
            Map.entry("equal", "=="),
            Map.entry("equals", "==")
    );

    static {
        Map<String, CheckOp> map = new java.util.HashMap<>();
        for (CheckOp op : values()) {
            map.put(op.symbol, op);
        }
        SYMBOL_MAP = Map.copyOf(map);
        ALL_SYMBOLS = java.util.Arrays.stream(values())
                .map(CheckOp::symbol)
                .collect(Collectors.joining(", "));
    }

    // ======================== 辅助方法 ========================

    private String describeExpectedTypes() {
        return supportedTypes.stream()
                .map(BaseType::name)
                .collect(Collectors.joining("/"));
    }

    private String buildTypeHint(IRType type) {
        // 根据操作符类别和实际类型给出更有针对性的提示
        return switch (this) {
            case GT, GTE, LT, LTE, BETWEEN ->
                    "Hint: use '==' for equality or 'contains' for collection membership.";
            case STARTS_WITH, ENDS_WITH, MATCHES ->
                    "Hint: use '==' for non-string equality checks.";
            case CONTAINS ->
                    "Hint: for numeric ranges, use 'between'; for set membership, use 'in'.";
            case IN -> {
                if (type == IRType.DOUBLE || type == IRType.LONG)
                    yield "Hint: use 'between' for numeric range checks, or '==' for exact equality.";
                if (type == IRType.BOOLEAN)
                    yield "Hint: boolean variables should use '== true' or '== false' directly.";
                yield "Hint: 'in' only supports STRING/INT/ENUM. Use '==' for equality or 'contains' for collection membership.";
            }
            default -> "";
        };
    }
}
