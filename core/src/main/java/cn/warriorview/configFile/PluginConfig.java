package cn.warriorview.configFile;

import gloomlib.configuration.api.ConfigurationFile;
import gloomlib.configuration.api.ConfigurationPart;
import gloomlib.configuration.api.annotation.Header;

/**
 * 从 {@code config.yml} 加载插件全局配置。
 *
 * <p>继承 {@link ConfigurationFile}，所有字段由 GloomLib 自动从 YAML 反序列化。</p>
 */
@Header({
        "WarriorView 全局配置",
        "修改后执行 /warriorview reload all 即可生效。"
})
public class PluginConfig extends ConfigurationFile {

    // ── 调度器 ──────────────────────────────────────────────────────────

    public Scheduler scheduler = new Scheduler();

    public static class Scheduler extends ConfigurationPart {
        /** 每 tick 最大执行任务数（限流）。 */
        public int maxTasksPerTick = 500;
        /** 连续空闲多少 tick 后进入休眠。 */
        public int idleThreshold = 600;
    }

    // ── 变体系统 ─────────────────────────────────────────────────────────

    public Variants variants = new Variants();

    public static class Variants extends ConfigurationPart {
        /**
         * 回退到 Bukkit 权限 API 时，变体缓存的轮询刷新间隔（调度器活跃 Tick）。
         * 默认 20 Tick ≈ 1 秒。使用 LuckPerms 时此项无效（事件驱动精确失效）。
         */
        public long permissionCacheRefreshTicks = 200;
    }

    // ── 插件集成 ─────────────────────────────────────────────────────────

    public Integrations integrations = new Integrations();

    public static class Integrations extends ConfigurationPart {
        public PlaceholderAPI placeholderapi = new PlaceholderAPI();
        public LuckPerms luckperms = new LuckPerms();

        public static class PlaceholderAPI extends ConfigurationPart {
            /** 是否启用 PlaceholderAPI 集成。 */
            public boolean enabled = true;
        }

        public static class LuckPerms extends ConfigurationPart {
            /**
             * 是否通过 LuckPerms API 进行高性能权限判断。
             *
             * <ul>
             *   <li>{@code true} + LuckPerms 已安装：权限查询直接访问 LuckPerms 内存缓存，
             *       玩家权限变更时由事件驱动精确失效。</li>
             *   <li>{@code true} + LuckPerms 未安装：自动回退到 Bukkit 权限 API + 轮询刷新。</li>
             *   <li>{@code false}：始终使用 Bukkit 权限 API + 轮询刷新，不尝试加载 LuckPerms。</li>
             * </ul>
             * <p>变体（variants）功能与此配置无关，均可正常工作；此配置只影响权限检查的实现方式。</p>
             */
            public boolean enabled = true;
        }
    }

    // ── 调试 ────────────────────────────────────────────────────────────

    /** 是否在控制台输出每次重载的耗时。 */
    public boolean timingLog = true;
}
