package cn.warriorview.script.codegen;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * ASM 字节码生成辅助工具类。
 * 集中管理高频复用的基础指令序列和包装箱逻辑，减轻 Handler 类的维护负担。
 */
public final class ASMUtils {

    private ASMUtils() {
    }

    /**
     * 发射：new Exception("msg"); throw;
     */
    public static void emitThrow(MethodVisitor mv, Class<? extends RuntimeException> exceptionClass, String message) {
        String internalName = exceptionClass.getName().replace('.', '/');
        mv.visitTypeInsn(Opcodes.NEW, internalName);
        mv.visitInsn(Opcodes.DUP);
        if (message != null) {
            mv.visitLdcInsn(message);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, internalName, "<init>", "(Ljava/lang/String;)V", false);
        } else {
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, internalName, "<init>", "()V", false);
        }
        mv.visitInsn(Opcodes.ATHROW);
    }

    /**
     * 发射：if (var == null) return;
     */
    public static void emitNullCheckReturn(MethodVisitor mv, int slot) {
        org.objectweb.asm.Label continueLabel = new org.objectweb.asm.Label();
        mv.visitVarInsn(Opcodes.ALOAD, slot);
        mv.visitJumpInsn(Opcodes.IFNONNULL, continueLabel);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitLabel(continueLabel);
    }

    /**
     * 发射：void return（RETURN 指令）
     */
    public static void emitVoidReturn(MethodVisitor mv) {
        mv.visitInsn(Opcodes.RETURN);
    }

    /**
     * 根据 Java 对象类型自动选择最优加载指令并将结果庋变为 {@code Object}。
     * <ul>
     * <li>{@code Integer} → {@code emitIntConst} + {@code Integer.valueOf}就</li>
     * <li>{@code Double/Float} → {@code emitDoubleConst} +
     * {@code Double.valueOf}</li>
     * <li>{@code Long} → {@code emitLongConst} + {@code Long.valueOf}</li>
     * <li>{@code Boolean} → {@code ICONST_0/1} + {@code Boolean.valueOf}</li>
     * <li>{@code String} → {@code LDC}（已是 reference，无需装箱）</li>
     * </ul>
     * <p>
     * 不支持的类型抛出 {@link IllegalArgumentException}。
     */
    public static void emitLiteral(MethodVisitor mv, Object value) {
        if (value instanceof Integer i) {
            emitIntConst(mv, i);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf",
                    "(I)Ljava/lang/Integer;", false);
        } else if (value instanceof Double d) {
            emitDoubleConst(mv, d);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf",
                    "(D)Ljava/lang/Double;", false);
        } else if (value instanceof Float f) {
            emitFloatConst(mv, f);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Float", "valueOf",
                    "(F)Ljava/lang/Float;", false);
        } else if (value instanceof Long l) {
            emitLongConst(mv, l);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Long", "valueOf",
                    "(J)Ljava/lang/Long;", false);
        } else if (value instanceof Boolean b) {
            mv.visitInsn(b ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf",
                    "(Z)Ljava/lang/Boolean;", false);
        } else if (value instanceof String s) {
            mv.visitLdcInsn(s); // String 已是 reference，直接压栈
        } else {
            throw new IllegalArgumentException("emitLiteral: unsupported literal type: "
                    + (value == null ? "null" : value.getClass().getName()));
        }
    }

    /**
     * 发射原始类型常量，不装箱，将栈顶元素直接为原始类型。
     * 用于 Action 调用有原始类型形参时，避免装箱再拆箱的反模式。
     */
    public static void emitPrimitiveLiteral(MethodVisitor mv, Object parsed, Class<?> primitiveType) {
        if (primitiveType == boolean.class) {
            mv.visitInsn(((Boolean) parsed) ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
        } else if (primitiveType == int.class) {
            emitIntConst(mv, ((Number) parsed).intValue());
        } else if (primitiveType == long.class) {
            emitLongConst(mv, ((Number) parsed).longValue());
        } else if (primitiveType == double.class) {
            emitDoubleConst(mv, ((Number) parsed).doubleValue());
        } else if (primitiveType == float.class) {
            emitFloatConst(mv, ((Number) parsed).floatValue());
        } else {
            throw new IllegalArgumentException("emitPrimitiveLiteral: not a primitive type: " + primitiveType);
        }
    }

    /**
     * 介局变量加载：根据 {@link cn.warriorview.script.core.ScriptIR.IRType} 发射对应 XLOAD，
     * 对于原始类型进行装箱，结果始终是 {@code Object}。
     * 封装 {@link ReturnNodeHandler} 中的重复模式为共享工具。
     */
    public static void emitLoadBoxed(MethodVisitor mv, int slot,
            cn.warriorview.script.core.ScriptIR.IRType type) {
        switch (type.base()) {
            case INT:
            case BOOLEAN:
                mv.visitVarInsn(Opcodes.ILOAD, slot);
                break;
            case LONG:
                mv.visitVarInsn(Opcodes.LLOAD, slot);
                break;
            case DOUBLE:
                mv.visitVarInsn(Opcodes.DLOAD, slot);
                break;
            default:
                mv.visitVarInsn(Opcodes.ALOAD, slot);
                break;
        }
        if (type.isPrimitive()) {
            emitBox(mv, type);
        }
    }

    /**
     * 发射：var.equals("...") 的比较序列
     */
    public static void emitEquals(MethodVisitor mv) {
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "equals", "(Ljava/lang/Object;)Z", false);
    }

    /**
     * 发射：Integer.valueOf() / Double.valueOf() 等装箱操作
     */
    public static void emitBox(MethodVisitor mv, cn.warriorview.script.core.ScriptIR.IRType type) {
        switch (type.base()) {
            case INT:
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;",
                        false);
                break;
            case DOUBLE:
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false);
                break;
            case LONG:
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false);
                break;
            case BOOLEAN:
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;",
                        false);
                break;
            default:
                break;
        }
    }

    /**
     * 发射：Number.intValue() / Number.doubleValue() 等拆箱操作
     */
    public static void emitUnbox(MethodVisitor mv, cn.warriorview.script.core.ScriptIR.IRType type) {
        switch (type.base()) {
            case INT:
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Number", "intValue", "()I", false);
                break;
            case DOUBLE:
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Number", "doubleValue", "()D", false);
                break;
            case LONG:
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Number", "longValue", "()J", false);
                break;
            case BOOLEAN:
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false);
                break;
            default:
                break;
        }
    }

    // ======================== 常量加载工具 ========================

    public static void emitIntConst(MethodVisitor mv, int value) {
        if (value >= -1 && value <= 5) {
            mv.visitInsn(Opcodes.ICONST_0 + value);
        } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
            mv.visitIntInsn(Opcodes.BIPUSH, value);
        } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
            mv.visitIntInsn(Opcodes.SIPUSH, value);
        } else {
            mv.visitLdcInsn(value);
        }
    }

    public static void emitLongConst(MethodVisitor mv, long value) {
        if (value == 0L) {
            mv.visitInsn(Opcodes.LCONST_0);
        } else if (value == 1L) {
            mv.visitInsn(Opcodes.LCONST_1);
        } else {
            mv.visitLdcInsn(value);
        }
    }

    public static void emitDoubleConst(MethodVisitor mv, double value) {
        if (value == 0.0d) {
            mv.visitInsn(Opcodes.DCONST_0);
        } else if (value == 1.0d) {
            mv.visitInsn(Opcodes.DCONST_1);
        } else {
            mv.visitLdcInsn(value);
        }
    }

    // ======================== 变量存取操作码 ========================

    /**
     * 根据 {@link cn.warriorview.script.core.ScriptIR.IRType} 返回对应的 XSTORE 操作码。
     * 集中维护 IRType 到字节码指令的权威映射，避免散落在各处的重复 switch。
     */
    public static int storeOpcode(cn.warriorview.script.core.ScriptIR.IRType type) {
        switch (type.base()) {
            case INT:
            case BOOLEAN:
                return Opcodes.ISTORE;
            case LONG:
                return Opcodes.LSTORE;
            case DOUBLE:
                return Opcodes.DSTORE;
            default:
                return Opcodes.ASTORE;
        }
    }

    // ======================== 对象工具方法 ========================

    /**
     * 发射：obj.hashCode()
     */
    public static void emitHashCode(MethodVisitor mv) {
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "hashCode", "()I", false);
    }

    /**
     * 发射：((Enum) obj).name() → 栈顶变为枚举名字符串
     */
    public static void emitEnumName(MethodVisitor mv) {
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Enum", "name", "()Ljava/lang/String;", false);
    }

    // ======================== 常量加载工具（补全 float） ========================

    /**
     * 发射 float 字面量的最优加载指令（FCONST_0/1/2 或 LDC）。
     */
    public static void emitFloatConst(MethodVisitor mv, float value) {
        if (value == 0.0f) {
            mv.visitInsn(Opcodes.FCONST_0);
        } else if (value == 1.0f) {
            mv.visitInsn(Opcodes.FCONST_1);
        } else if (value == 2.0f) {
            mv.visitInsn(Opcodes.FCONST_2);
        } else {
            mv.visitLdcInsn(value);
        }
    }

    // ======================== 跳转取反映射 ========================

    /**
     * 将 JVM 条件跳转 opcode 翻转为反义 opcode（如 IFEQ → IFNE, IFLT → IFGE）。
     * 零额外指令开销，纯编译期映射表。
     *
     * @throws IllegalArgumentException 当 opcode 不是标准条件跳转时
     */
    public static int invertJump(int opcode) {
        return switch (opcode) {
            case Opcodes.IFEQ -> Opcodes.IFNE;
            case Opcodes.IFNE -> Opcodes.IFEQ;
            case Opcodes.IFLT -> Opcodes.IFGE;
            case Opcodes.IFGE -> Opcodes.IFLT;
            case Opcodes.IFGT -> Opcodes.IFLE;
            case Opcodes.IFLE -> Opcodes.IFGT;
            case Opcodes.IF_ICMPEQ -> Opcodes.IF_ICMPNE;
            case Opcodes.IF_ICMPNE -> Opcodes.IF_ICMPEQ;
            case Opcodes.IF_ICMPLT -> Opcodes.IF_ICMPGE;
            case Opcodes.IF_ICMPGE -> Opcodes.IF_ICMPLT;
            case Opcodes.IF_ICMPGT -> Opcodes.IF_ICMPLE;
            case Opcodes.IF_ICMPLE -> Opcodes.IF_ICMPGT;
            case Opcodes.IF_ACMPEQ -> Opcodes.IF_ACMPNE;
            case Opcodes.IF_ACMPNE -> Opcodes.IF_ACMPEQ;
            case Opcodes.IFNULL -> Opcodes.IFNONNULL;
            case Opcodes.IFNONNULL -> Opcodes.IFNULL;
            default -> throw new IllegalArgumentException("Cannot invert opcode: " + opcode);
        };
    }
}
