package cn.warriorview.api.manager;

/**
 * 命令管理器接口。
 * <p>
 * 用于动态注册和管理插件命令。
 */
public interface CommandManager {

    /**
     * 动态注册一个命令。
     *
     * @param name     命令名称（不含 /）。
     * @param executor 命令执行器（应为 {@link org.bukkit.command.CommandExecutor}）。
     */
    void registerCommand(String name, Object executor);

    /**
     * 注销一个命令。
     *
     * @param name 命令名称。
     */
    void unregisterCommand(String name);
}
