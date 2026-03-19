package cn.warriorview.integration;

import org.bukkit.entity.Player;

/**
 * 权限查询抽象，解耦 Bukkit 标准权限 API 与 LuckPerms 高性能缓存实现。
 *
 * <p>所有实现均应直接读取内存缓存，不得执行阻塞 I/O 或重量级锁竞争。</p>
 */
@FunctionalInterface
public interface PermissionChecker {

    /**
     * 检查玩家是否拥有指定权限节点。
     *
     * @param player     在线玩家
     * @param permission 权限节点字符串
     * @return {@code true} 若玩家拥有该权限
     */
    boolean hasPermission(Player player, String permission);

    /**
     * 默认实现：委托给 Bukkit 权限 API。
     * 安装 LuckPerms 后，此路径已由 LuckPerms 的内部缓存支持（仍经 Bukkit 代理层）。
     * 启用 LuckPerms 集成后将被 {@link LuckPermsIntegration#buildChecker()} 替换。
     */
    PermissionChecker BUKKIT = Player::hasPermission;
}
