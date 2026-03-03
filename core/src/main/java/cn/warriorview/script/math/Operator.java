package cn.warriorview.script.math;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.function.DoubleBinaryOperator;

/**
 * 二元运算符。
 *
 * <p>
 * 每个常量自带：
 * <ul>
 * <li>{@code symbol}：解析器识别符（可为多字符，如 {@code "=="}）</li>
 * <li>{@code precedence}：优先级（越大越先算；比较运算符 0、AND -1、OR -2）</li>
 * <li>{@code leftAssociative}：是否左结合（{@code ^} 右结合）</li>
 * <li>{@link #apply}：运行时 / 常量折叠期求值</li>
 * <li>{@link #emit}：发射对应的 JVM 字节码</li>
 * </ul>
 *
 * <p>
 * 扩展新运算符只需在此处增加枚举常量，无需修改任何 switch 语句。
 */
public enum Operator {

    // ── 算术运算符（优先级 1-3）─────────────────────────────────────────────

    ADD("+", 1, true, (l, r) -> l + r, Opcodes.DADD),
    SUBTRACT("-", 1, true, (l, r) -> l - r, Opcodes.DSUB),
    MULTIPLY("*", 2, true, (l, r) -> l * r, Opcodes.DMUL),
    DIVIDE("/", 2, true, (l, r) -> l / r, Opcodes.DDIV),
    MODULO("%", 2, true, (l, r) -> l % r, Opcodes.DREM),

    /** 右结合，ASM 通过 {@code Math.pow} 实现，无对应单条指令。 */
    POWER("^", 3, false, Math::pow, -1) {
        @Override
        public void emit(MethodVisitor mv) {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", "pow", "(DD)D", false);
        }
    },

    // ── 比较运算符（优先级 0；结果为 0.0 / 1.0）──────────────────────────────

    /** {@code a == b} → 1.0 if equal, 0.0 otherwise */
    EQ("==", 0, true, (l, r) -> l == r ? 1.0 : 0.0, -1) {
        @Override
        public void emit(MethodVisitor mv) {
            MathNodeEmitter.emitCompare(mv, Opcodes.IFEQ);
        }
    },
    /** {@code a != b} */
    NEQ("!=", 0, true, (l, r) -> l != r ? 1.0 : 0.0, -1) {
        @Override
        public void emit(MethodVisitor mv) {
            MathNodeEmitter.emitCompare(mv, Opcodes.IFNE);
        }
    },
    /** {@code a > b} */
    GT(">", 0, true, (l, r) -> l > r ? 1.0 : 0.0, -1) {
        @Override
        public void emit(MethodVisitor mv) {
            MathNodeEmitter.emitCompare(mv, Opcodes.IFGT);
        }
    },
    /** {@code a < b} */
    LT("<", 0, true, (l, r) -> l < r ? 1.0 : 0.0, -1) {
        @Override
        public void emit(MethodVisitor mv) {
            MathNodeEmitter.emitCompare(mv, Opcodes.IFLT);
        }
    },
    /** {@code a >= b} */
    GTE(">=", 0, true, (l, r) -> l >= r ? 1.0 : 0.0, -1) {
        @Override
        public void emit(MethodVisitor mv) {
            MathNodeEmitter.emitCompare(mv, Opcodes.IFGE);
        }
    },
    /** {@code a <= b} */
    LTE("<=", 0, true, (l, r) -> l <= r ? 1.0 : 0.0, -1) {
        @Override
        public void emit(MethodVisitor mv) {
            MathNodeEmitter.emitCompare(mv, Opcodes.IFLE);
        }
    },

    // ── 布尔运算符（短路语义由 MathNodeEmitter 专门处理）─────────────────────

    /**
     * 逻辑与 {@code &&}（优先级高于 ||，低于比较）。
     * 字节码发射交由 {@link MathNodeEmitter#emitBinary} 处理短路逻辑；
     * {@link #apply} 用于常量折叠仍以非短路方式计算（编译期结果相同）。
     */
    AND("&&", -1, true, (l, r) -> (l != 0.0 && r != 0.0) ? 1.0 : 0.0, -1) {
        @Override
        public void emit(MethodVisitor mv) {
            throw new UnsupportedOperationException("AND must be emitted via MathNodeEmitter (short-circuit)");
        }
    },
    /**
     * 逻辑或 {@code ||}（最低优先级）。
     * 同上，由 {@link MathNodeEmitter#emitBinary} 处理短路逻辑。
     */
    OR("||", -2, true, (l, r) -> (l != 0.0 || r != 0.0) ? 1.0 : 0.0, -1) {
        @Override
        public void emit(MethodVisitor mv) {
            throw new UnsupportedOperationException("OR must be emitted via MathNodeEmitter (short-circuit)");
        }
    };

    // ─────────────────────────────────────────────────────────────────────────

    private final String symbol;
    private final int precedence;
    private final boolean leftAssociative;
    private final DoubleBinaryOperator evaluator;
    /**
     * 对应的 JVM 算术指令 opcode（{@code Opcodes.DADD} 等）。
     * {@code -1} 表示需覆盖 {@link #emit} 自定义发射（POWER / 比较 / 布尔）。
     */
    private final int opcode;

    Operator(String symbol, int precedence, boolean leftAssociative,
            DoubleBinaryOperator evaluator, int opcode) {
        this.symbol = symbol;
        this.precedence = precedence;
        this.leftAssociative = leftAssociative;
        this.evaluator = evaluator;
        this.opcode = opcode;
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /** 返回运算符符号字符串（如 {@code "+"}, {@code "=="}）。 */
    public String getSymbol() {
        return symbol;
    }

    public int getPrecedence() {
        return precedence;
    }

    public boolean isLeftAssociative() {
        return leftAssociative;
    }

    /**
     * 运行时 / 编译期常量折叠求值。
     *
     * @param l 左操作数
     * @param r 右操作数
     * @return 计算结果
     */
    public double apply(double l, double r) {
        return evaluator.applyAsDouble(l, r);
    }

    /**
     * 发射对应的 JVM 字节码（两操作数已在操作数栈顶）。
     * 默认发射单条算术指令；特殊运算符（POWER/比较/布尔）需覆盖此方法。
     */
    public void emit(MethodVisitor mv) {
        mv.visitInsn(opcode);
    }

    // ── Lookup ───────────────────────────────────────────────────────────────

    /**
     * 按字符串符号查找运算符（主方法，支持多字符如 {@code "=="}）。
     *
     * @return 匹配的 {@link Operator}，未找到时返回 {@code null}
     */
    public static Operator fromSymbol(String s) {
        for (Operator op : values()) {
            if (op.symbol.equals(s))
                return op;
        }
        return null;
    }

    /**
     * 按单字符查找运算符（向后兼容方法）。
     *
     * @see #fromSymbol(String)
     */
    public static Operator fromSymbol(char c) {
        return fromSymbol(String.valueOf(c));
    }
}
