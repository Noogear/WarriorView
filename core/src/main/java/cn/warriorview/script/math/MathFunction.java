package cn.warriorview.script.math;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleBinaryOperator;
import java.util.function.DoubleUnaryOperator;
import java.util.function.Function;


/**
 * 内置数学函数。
 *
 * <p>
 * 每个枚举常量自带：
 * <ul>
 * <li>{@code argCount}：参数个数</li>
 * <li>{@code eval}：运行时求值（树遍历 + 常量折叠路径）</li>
 * <li>{@code foldable}：是否可在编译期常量折叠（RAND 不可折叠）</li>
 * <li>{@link #emit}：直接发射 ASM 字节码</li>
 * </ul>
 *
 * <p>
 * 扩展新函数只需在此处增加一个枚举常量，无需修改任何 switch 语句。
 */
public enum MathFunction {

    // ── 1 元函数 ─────────────────────────────────────────────────────────────
    ABS(1, a -> Math.abs(a[0]), "abs"),
    ROUND(1, a -> (double) Math.round(a[0]), null) {
        @Override
        public void emit(MethodVisitor mv, int argCount) {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", "round", "(D)J", false);
            mv.visitInsn(Opcodes.L2D);
        }
    },
    CEIL(1, a -> Math.ceil(a[0]), "ceil"),
    FLOOR(1, a -> Math.floor(a[0]), "floor"),
    SQRT(1, a -> Math.sqrt(a[0]), "sqrt"),
    CBRT(1, a -> Math.cbrt(a[0]), "cbrt"),
    SIN(1, a -> Math.sin(a[0]), "sin"),
    COS(1, a -> Math.cos(a[0]), "cos"),
    TAN(1, a -> Math.tan(a[0]), "tan"),
    ASIN(1, a -> Math.asin(a[0]), "asin"),
    ACOS(1, a -> Math.acos(a[0]), "acos"),
    ATAN(1, a -> Math.atan(a[0]), "atan"),
    SINH(1, a -> Math.sinh(a[0]), "sinh"),
    COSH(1, a -> Math.cosh(a[0]), "cosh"),
    TANH(1, a -> Math.tanh(a[0]), "tanh"),
    LOG(1, a -> Math.log10(a[0]), "log10"),
    LN(1, a -> Math.log(a[0]), "log"),

    // ── 2 元函数 ─────────────────────────────────────────────────────────────
    MIN(2, a -> Math.min(a[0], a[1]), "min"),
    MAX(2, a -> Math.max(a[0], a[1]), "max"),

    // ── 特殊：不可折叠，发射时顺序特殊 ──────────────────────────────────────
    RAND(1, null, null) {
        /** RAND 在运行时才能求值，常量折叠不适用。 */
        @Override
        public boolean isFoldable() {
            return false;
        }

        @Override
        public double apply(double[] args) {
            return Math.random() * args[0];
        }

        @Override
        public void emit(MethodVisitor mv, int argCount) {
            // args[0] 已在栈顶；先调用 Math.random()，再 DMUL
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", "random", "()D", false);
            mv.visitInsn(Opcodes.DMUL);
        }
    };

    // ─────────────────────────────────────────────────────────────────────────

    /** 参数个数。 */
    private final int argCount;

    /**
     * 运行时/折叠期评估函数（接受 double[] 参数）。
     * RAND 覆盖了 {@link #apply}，此字段为 null。
     */
    private final java.util.function.Function<double[], Double> evaluator;

    /**
     * 对应的 JVM {@code java/lang/Math} 方法名。
     * {@code null} 表示需要覆盖 {@link #emit} 自定义发射（ROUND、RAND）。
     */
    private final String jvmMethodName;

    MathFunction(int argCount,
            java.util.function.Function<double[], Double> evaluator,
            String jvmMethodName) {
        this.argCount = argCount;
        this.evaluator = evaluator;
        this.jvmMethodName = jvmMethodName;
    }

    // ── Public API ───────────────────────────────────────────────────────────

    public int getArgCount() {
        return argCount;
    }

    /** 是否可在编译期对纯常量参数进行常量折叠。默认 true；RAND 覆盖为 false。 */
    public boolean isFoldable() {
        return true;
    }

    /**
     * 运行时 / 编译期折叠求值。
     *
     * @param args 参数数组，长度必须等于 {@link #getArgCount()}
     */
    public double apply(double[] args) {
        assert evaluator != null : name() + " must override apply()";
        return evaluator.apply(args);
    }

    /**
     * 发射调用该函数的 JVM 字节码（参数已在操作数栈上）。
     *
     * <p>
     * 默认实现调用 {@code java/lang/Math.<jvmMethodName>}，
     * 特殊函数（ROUND、RAND）通过覆盖此方法自定义。
     *
     * @param mv       目标 {@link MethodVisitor}
     * @param argCount 参数个数（决定方法描述符 {@code (D)D} 或 {@code (DD)D}）
     */
    public void emit(MethodVisitor mv, int argCount) {
        String desc = argCount == 1 ? "(D)D" : "(DD)D";
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", jvmMethodName, desc, false);
    }

    // ── Lookup (built-in) ────────────────────────────────────────────────────

    public static MathFunction fromName(String name) {
        try {
            return valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ── Custom function registry ─────────────────────────────────────────────

    /**
     * 已注册的自定义函数记录。
     *
     * @param argCount  参数个数（1–16）
     * @param evaluator 求值函数
     * @param foldable  是否允许编译期常量折叠
     */
    public record RegisteredFunction(
            int argCount,
            Function<double[], Double> evaluator,
            boolean foldable) {
    }

    /** ASM INVOKESTATIC 目标的内部类名（指向 MathFunction 本身）。 */
    static final String CUSTOM_INTERNAL_NAME =
            MathFunction.class.getName().replace('.', '/');

    private static final ConcurrentHashMap<String, RegisteredFunction> CUSTOM_REGISTRY =
            new ConcurrentHashMap<>();

    static {
        // clamp(a, min, max) → 将 a 限制在 [min, max] 范围内
        register("clamp", 3, args -> Math.max(args[1], Math.min(args[2], args[0])));
        // lerp(a, b, t) → 线性插值：a + (b-a)*t
        register("lerp", 3, args -> args[0] + (args[1] - args[0]) * args[2]);
        // saturate(x) → clamp 到 [0, 1]
        register("saturate", 1, args -> Math.max(0.0, Math.min(1.0, args[0])));
        // sign(x) → -1 / 0 / 1
        register("sign", 1, args -> Math.signum(args[0]));
        // step(edge, x) → x >= edge ? 1.0 : 0.0
        register("step", 2, args -> args[1] >= args[0] ? 1.0 : 0.0);
        // smoothstep(edge0, edge1, x) → GLSL 标准 smoothstep
        register("smoothstep", 3, args -> {
            double t = Math.max(0.0, Math.min(1.0, (args[2] - args[0]) / (args[1] - args[0])));
            return t * t * (3.0 - 2.0 * t);
        });
        // map(x, inMin, inMax, outMin, outMax) → 线性重映射
        register("map", 5, args -> args[3] + (args[4] - args[3]) * ((args[0] - args[1]) / (args[2] - args[1])));
    }

    /**
     * 注册自定义函数（可常量折叠）。
     */
    public static void register(String name, int argCount,
            Function<double[], Double> evaluator) {
        register(name, argCount, evaluator, true);
    }

    /**
     * 注册自定义函数。
     *
     * @param foldable 是否允许常量折叠
     */
    public static void register(String name, int argCount,
            Function<double[], Double> evaluator, boolean foldable) {
        if (argCount < 1 || argCount > 16)
            throw new IllegalArgumentException("argCount must be 1-16, got: " + argCount);
        CUSTOM_REGISTRY.put(name.toLowerCase(),
                new RegisteredFunction(argCount, evaluator, foldable));
    }

    /** 注册一元函数的便捷方法。 */
    public static void register(String name, DoubleUnaryOperator op) {
        register(name, 1, args -> op.applyAsDouble(args[0]), true);
    }

    /** 注册二元函数的便捷方法。 */
    public static void register(String name, DoubleBinaryOperator op) {
        register(name, 2, args -> op.applyAsDouble(args[0], args[1]), true);
    }

    /** 取消注册自定义函数。 */
    public static void unregister(String name) {
        CUSTOM_REGISTRY.remove(name.toLowerCase());
    }

    /**
     * 查询已注册的自定义函数。
     *
     * @return 注册记录，未找到返回 {@code null}
     */
    public static RegisteredFunction lookupCustom(String name) {
        return CUSTOM_REGISTRY.get(name.toLowerCase());
    }

    // ── 运行时求值入口（ASM INVOKESTATIC 目标）──────────────────────────────

    /** 通用入口：参数通过 {@code double[]} 传递。 */
    public static double invoke(String name, double[] args) {
        RegisteredFunction func = CUSTOM_REGISTRY.get(name);
        if (func == null)
            throw new IllegalStateException("Custom math function not found at runtime: " + name);
        return func.evaluator().apply(args);
    }

    /** 1 参数特化入口——消除 {@code double[]} 数组分配。 */
    public static double invoke1(String name, double a0) {
        RegisteredFunction func = CUSTOM_REGISTRY.get(name);
        if (func == null)
            throw new IllegalStateException("Custom math function not found at runtime: " + name);
        return func.evaluator().apply(new double[]{ a0 });
    }

    /** 2 参数特化入口——消除 {@code double[]} 数组分配。 */
    public static double invoke2(String name, double a0, double a1) {
        RegisteredFunction func = CUSTOM_REGISTRY.get(name);
        if (func == null)
            throw new IllegalStateException("Custom math function not found at runtime: " + name);
        return func.evaluator().apply(new double[]{ a0, a1 });
    }

    /** 3 参数特化入口——消除 {@code double[]} 数组分配。 */
    public static double invoke3(String name, double a0, double a1, double a2) {
        RegisteredFunction func = CUSTOM_REGISTRY.get(name);
        if (func == null)
            throw new IllegalStateException("Custom math function not found at runtime: " + name);
        return func.evaluator().apply(new double[]{ a0, a1, a2 });
    }

    // ── ASM 发射（自定义函数）────────────────────────────────────────────────

    /**
     * 发射 INVOKESTATIC 调用自定义函数的字节码。
     *
     * <p>1–3 参数使用特化入口（无数组分配），≥4 参数走通用数组路径。
     * 调用前函数名常量已由调用方压栈，参数值已按顺序压栈。
     */
    static void emitCustom(MethodVisitor mv, String name, int argCount) {
        if (argCount <= 3) {
            String desc = switch (argCount) {
                case 1 -> "(Ljava/lang/String;D)D";
                case 2 -> "(Ljava/lang/String;DD)D";
                case 3 -> "(Ljava/lang/String;DDD)D";
                default -> throw new AssertionError();
            };
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, CUSTOM_INTERNAL_NAME,
                    "invoke" + argCount, desc, false);
        } else {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, CUSTOM_INTERNAL_NAME,
                    "invoke", "(Ljava/lang/String;[D)D", false);
        }
    }
}
