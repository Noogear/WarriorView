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
}
