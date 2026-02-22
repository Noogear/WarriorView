package cn.warriorview.script.api;

import java.util.function.Consumer;

/**
 * 脚本宿主环境接口 (SPI)。
 * <p>
 * 提供向目标环境（如 Bukkit/Paper、BungeeCord、Minestom 等）注册脚本事件和卸载的抽象能力。
 * 平台插件需要实现此接口以桥接底层事件 API。
 */
public interface ScriptHost {

    /**
     * 向宿主环境注册事件监听，并返回代表该注册凭证的对象（用于后续卸载）。
     *
     * @param payloadClass 监听的荷载类（如 Bukkit Event，取决于使用场景）
     * @param priority     通过数字表征的优先级（从脚本中提取，解析由宿主决定）
     * @param handler      编译后生成的热回调函数，用于响应特定事件的触发
     * @return 注册标识/凭证 token
     */
    Object registerEvent(Class<?> payloadClass, int priority, Consumer<Object> handler);

    /**
     * 卸载由注册时返回的凭证代表的事件回调。
     *
     * @param registrationToken 之前 registerEvent 返回的标识
     */
    void unregisterEvent(Object registrationToken);
}
