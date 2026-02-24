package cn.warriorview.script.parser.accessor;

import com.google.common.reflect.TypeToken;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import java.lang.reflect.Method;

import static org.objectweb.asm.Opcodes.INVOKEINTERFACE;
import static org.objectweb.asm.Opcodes.INVOKEVIRTUAL;

/**
 * 基于标准 Getter 方法的属性提取器。
 */
public record MethodAccessor(Method method, TypeToken<?> returnType) implements PropertyAccessor {

    @Override
    public void emitLoad(MethodVisitor mv, boolean isOwnerInterface) {
        String ownerInternal = Type.getInternalName(method.getDeclaringClass());
        String methodDescriptor = Type.getMethodDescriptor(method);

        int opcode = isOwnerInterface ? INVOKEINTERFACE : INVOKEVIRTUAL;
        mv.visitMethodInsn(opcode, ownerInternal, method.getName(), methodDescriptor, isOwnerInterface);
    }
}
