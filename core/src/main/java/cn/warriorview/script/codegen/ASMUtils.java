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
     * 发射：var.equals("...") 的比较序列
     */
    public static void emitEquals(MethodVisitor mv) {
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "equals", "(Ljava/lang/Object;)Z", false);
    }

    /**
     * 发射：Integer.valueOf() / Double.valueOf() 等装箱操作
     */
    public static void emitBox(MethodVisitor mv, cn.warriorview.script.core.ScriptIR.IRType type) {
        switch (type) {
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
        switch (type) {
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
        return switch (type) {
            case INT, BOOLEAN -> Opcodes.ISTORE;
            case LONG -> Opcodes.LSTORE;
            case DOUBLE -> Opcodes.DSTORE;
            default -> Opcodes.ASTORE;
        };
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
