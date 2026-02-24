package cn.warriorview.script.parser.accessor;

import com.google.common.reflect.TypeToken;
import org.objectweb.asm.MethodVisitor;

/**
 * 属性存取器接口。
 * 代表从某一级数据源对象中提取下一级数据的操作抽象。
 */
public interface PropertyAccessor {

    /**
     * @return 当前取值操作返回的数据的确切类型（带泛型信息）
     */
    TypeToken<?> returnType();

    /**
     * 在 ASM 字节码生成期间，向栈上写入提取该数据的具体指令。
     * <p>
     * 调用此方法前，栈顶必须已经压入其宿主对象（Owner）。
     * 调用结束后，栈顶将变为提取到的返回值对象。
     *
     * @param mv               方法访问器
     * @param isOwnerInterface 宿主是否为接口（用于决定 INVOKEVIRTUAL / INVOKEINTERFACE）
     */
    void emitLoad(MethodVisitor mv, boolean isOwnerInterface);
}
