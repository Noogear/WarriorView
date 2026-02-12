package cn.warriorview.listener;

import org.bukkit.event.Event;

/**
 * 生成类的统一契约接口。
 * 由 {@link cn.warriorview.listener.AsmLogicCompiler} 编译的字节码类实现此接口，
 * {@link cn.warriorview.listener.DynamicListenerRuntime} 通过多态调用执行逻辑并实现热替换。
 */
@FunctionalInterface
public interface GeneratedLogic {

    /** 执行编译后的事件处理逻辑 */
    void execute(Event event);
}
