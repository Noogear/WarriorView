package cn.warriorview.listener;

import java.lang.invoke.MethodType;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;

/**
 * JVM 类型辅助工具。
 * 提供 {@link org.objectweb.asm.commons.GeneratorAdapter} 无法直接覆盖的补充能力：
 * 类型判断、操作符映射、{@link java.lang.StringBuilder#append} 描述符。
 */
public final class JvmTypeUtils implements Opcodes {

    private JvmTypeUtils() {
    }

    /** 判断类型是否可进行数值比较（基本数值类型 + 装箱类型 + {@link java.lang.Number} 子类） */
    public static boolean isNumericType(Class<?> type) {
        if (type == null)
            return false;
        if (type.isPrimitive())
            return type != boolean.class && type != void.class;
        return Number.class.isAssignableFrom(type);
    }

    /**
     * 操作符 → {@link org.objectweb.asm.commons.GeneratorAdapter} 比较模式。
     * 语义：条件不满足时跳转 fail。
     */
    public static int toCompareMode(String op) {
        return switch (op) {
            case "==" -> GeneratorAdapter.NE;
            case "!=" -> GeneratorAdapter.EQ;
            case ">" -> GeneratorAdapter.LE;
            case ">=" -> GeneratorAdapter.LT;
            case "<" -> GeneratorAdapter.GE;
            case "<=" -> GeneratorAdapter.GT;
            default -> GeneratorAdapter.NE;
        };
    }

    private static final String APPEND_OBJECT = "(Ljava/lang/Object;)Ljava/lang/StringBuilder;";

    /** 获取 {@link java.lang.StringBuilder#append} 对应此类型的方法描述符 */
    public static String appendDescriptor(Class<?> type) {
        if (type == null || !type.isPrimitive())
            return APPEND_OBJECT;
        if (type == double.class)
            return "(D)Ljava/lang/StringBuilder;";
        if (type == float.class)
            return "(F)Ljava/lang/StringBuilder;";
        if (type == long.class)
            return "(J)Ljava/lang/StringBuilder;";
        if (type == int.class)
            return "(I)Ljava/lang/StringBuilder;";
        if (type == boolean.class)
            return "(Z)Ljava/lang/StringBuilder;";
        return "(I)Ljava/lang/StringBuilder;";
    }

    /**
     * 获取数值比较前需提升到的 ASM 基本类型。
     * 装箱类型通过 {@link java.lang.invoke.MethodType#unwrap()} 自动解箱，
     * short/byte 提升为 int（JVM 栈上等宽）。
     */
    public static Type numericPrimitiveType(Class<?> type) {
        if (type.isPrimitive())
            return Type.getType(type);

        // MethodType.unwrap：Integer→int, Double→double, Long→long ...
        Class<?> unwrapped = MethodType.methodType(type).unwrap().returnType();
        if (unwrapped != type) {
            // short/byte 在 JVM 操作数栈上是 int 宽度，需提升
            return (unwrapped == short.class || unwrapped == byte.class)
                    ? Type.INT_TYPE
                    : Type.getType(unwrapped);
        }

        // Number 非标准子类（BigDecimal, BigInteger 等）→ double
        if (Number.class.isAssignableFrom(type))
            return Type.DOUBLE_TYPE;
        throw new IllegalArgumentException("非数值类型: " + type.getName());
    }
}
