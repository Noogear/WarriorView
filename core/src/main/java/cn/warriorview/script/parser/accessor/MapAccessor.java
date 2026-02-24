package cn.warriorview.script.parser.accessor;

import com.google.common.reflect.TypeToken;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import static org.objectweb.asm.Opcodes.CHECKCAST;
import static org.objectweb.asm.Opcodes.INVOKEINTERFACE;

/**
 * 基于 {@link java.util.Map} 键名取值的属性提取器。
 */
public record MapAccessor(String key, TypeToken<?> returnType) implements PropertyAccessor {

    @Override
    public void emitLoad(MethodVisitor mv, boolean isOwnerInterface) {
        // Map.get(Object)
        mv.visitLdcInsn(key);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", true);

        // 强制泛型转正
        if (returnType.getRawType() != Object.class) {
            mv.visitTypeInsn(CHECKCAST, Type.getInternalName(returnType.getRawType()));
        }
    }
}
