package cn.warriorview.script.math;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;


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

    // ── Lookup ───────────────────────────────────────────────────────────────

    public static MathFunction fromName(String name) {
        try {
            return valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
