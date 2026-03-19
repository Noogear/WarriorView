package cn.warriorview.integration;

import cn.warriorview.util.Log;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.context.ContextManager;
import net.luckperms.api.event.user.UserDataRecalculateEvent;
import net.luckperms.api.model.user.User;
import net.luckperms.api.model.user.UserManager;
import org.bukkit.plugin.Plugin;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * LuckPerms 集成：提供高性能权限检查器，并在权限变更时通知变体缓存失效。
 *
 * <h3>高性能原理</h3>
 * <ul>
 *   <li>通过 {@link LuckPermsProvider} 直接访问 LuckPerms API，绕过 Bukkit 权限代理层。</li>
 *   <li>在线玩家的 {@link User} 及其 {@code CachedPermissionData} 始终保存在内存中，
 *       单次查询延迟约 100–300 ns（纯内存，无 I/O，无锁竞争）。</li>
 * </ul>
 *
 * <h3>缓存失效机制</h3>
 * <p>订阅 {@link UserDataRecalculateEvent}：当玩家权限发生任何变更（管理员授权/撤权、
 * 权限组变动等）时，LuckPerms 触发此事件，进而调用 {@code invalidateCallback}
 * 清除对应玩家的变体缓存，确保下次查询反映最新权限状态。</p>
 */
public final class LuckPermsIntegration {

    private final LuckPerms api;

    private LuckPermsIntegration(LuckPerms api) {
        this.api = api;
    }

    /**
     * 尝试加载 LuckPerms 集成。若 LuckPerms 未安装或初始化失败，返回 {@code null}。
     *
     * @param plugin             用于注册 LuckPerms 事件监听的插件实例
     * @param invalidateCallback 玩家权限重新计算时的失效回调（参数：玩家 UUID）
     * @return 集成实例；若 LuckPerms 不可用则返回 {@code null}
     */
    public static LuckPermsIntegration tryLoad(Plugin plugin, Consumer<UUID> invalidateCallback) {
        if (plugin.getServer().getPluginManager().getPlugin("LuckPerms") == null) {
            return null;
        }
        try {
            LuckPerms lp = LuckPermsProvider.get();
            LuckPermsIntegration integration = new LuckPermsIntegration(lp);
            lp.getEventBus().subscribe(plugin, UserDataRecalculateEvent.class,
                    event -> invalidateCallback.accept(event.getUser().getUniqueId()));
            Log.info("[Integration] LuckPerms integration loaded successfully.");
            return integration;
        } catch (Exception e) {
            Log.warn("[Integration] LuckPerms integration failed to load, falling back to Bukkit permissions API: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 构建基于 LuckPerms 内存缓存的高性能权限检查器。
     *
     * <p>加载期将 {@code UserManager} 与 {@code ContextManager} 捕获为闭包变量，
     * 运行期直接访问，无需经由 {@link LuckPerms} 字段间接读取。</p>
     *
     * @return 高性能 {@link PermissionChecker}
     */
    public PermissionChecker buildChecker() {
        // 加载期一次性捕获，闭包直接持有引用，运行期零间接寻址
        UserManager    userManager    = api.getUserManager();
        ContextManager contextManager = api.getContextManager();
        return (player, permission) -> {
            User user = userManager.getUser(player.getUniqueId());
            if (user == null) return false; // 极罕见：LP 初始化期间，快速出口
            return user.getCachedData()
                    .getPermissionData(contextManager.getQueryOptions(player))
                    .checkPermission(permission)
                    .asBoolean();
        };
    }
}
