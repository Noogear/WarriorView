package cn.warriorview.script.parser.accessor;

import com.google.common.reflect.TypeToken;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import static org.objectweb.asm.Opcodes.CHECKCAST;
import static org.objectweb.asm.Opcodes.INVOKEINTERFACE;

/**
 * 基于 {@link java.util.List} 数字索引取值的属性提取器。
 */
public record ListAccessor(int index, TypeToken<?> returnType) implements PropertyAccessor {

    @Override
    public void emitLoad(MethodVisitor mv) {
        // 装填数字索引，需要将 int 常量压入栈
        switch (index) {
            case 0 -> mv.visitInsn(org.objectweb.asm.Opcodes.ICONST_0);
            case 1 -> mv.visitInsn(org.objectweb.asm.Opcodes.ICONST_1);
            case 2 -> mv.visitInsn(org.objectweb.asm.Opcodes.ICONST_2);
            case 3 -> mv.visitInsn(org.objectweb.asm.Opcodes.ICONST_3);
            case 4 -> mv.visitInsn(org.objectweb.asm.Opcodes.ICONST_4);
            case 5 -> mv.visitInsn(org.objectweb.asm.Opcodes.ICONST_5);
            default -> {
                if (index >= Byte.MIN_VALUE && index <= Byte.MAX_VALUE) {
                    mv.visitIntInsn(org.objectweb.asm.Opcodes.BIPUSH, index);
                } else if (index >= Short.MIN_VALUE && index <= Short.MAX_VALUE) {
                    mv.visitIntInsn(org.objectweb.asm.Opcodes.SIPUSH, index);
                } else {
                    mv.visitLdcInsn(index);
                }
            }
        }

        // List.get(int)
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "get", "(I)Ljava/lang/Object;", true);

        // 强制泛型转正
        if (returnType.getRawType() != Object.class) {
            mv.visitTypeInsn(CHECKCAST, Type.getInternalName(returnType.getRawType()));
        }
    }
}
