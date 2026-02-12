package cn.warriorview.api;

import cn.warriorview.api.manager.CommandManager;
import cn.warriorview.api.manager.ConfigManager;
import cn.warriorview.api.manager.FileManager;

/**
 * WarriorView 服务提供者接口。
 * <p>
 * Core 模块的主类需要实现此接口，
 * 以便 API 模块可以通过 {@link WarriorViewAPI#getProvider()} 获取到实现。
 */
public interface WarriorView {

    /**
     * 获取配置管理器。
     *
     * @return 配置管理器实例。
     */
    ConfigManager getConfigManager();

    /**
     * 获取命令管理器。
     *
     * @return 命令管理器实例。
     */
    CommandManager getCommandManager();

    /**
     * 获取文件管理器。
     *
     * @return 文件管理器实例。
     */
    FileManager getFileManager();

    /**
     * 获取插件版本。
     *
     * @return 版本号字符串。
     */
    String getVersion();
}
