package cn.warriorview.integration;

import cn.warriorview.configFile.PluginConfig;
import cn.warriorview.listener.IndicatorHandler;
import cn.warriorview.util.Log;
import cn.warriorview.util.RapidTransientScheduler;
import org.bukkit.plugin.Plugin;

import java.util.function.UnaryOperator;

/**
 * 管理 PlaceholderAPI 与 LuckPerms 集成的生命周期，以及 Bukkit 回退的变体轮询 daemon。
 *
 * <h3>两阶段初始化</h3>
 * <ol>
 *   <li>{@link #IntegrationManager(Plugin, PluginConfig)} — 在 Formatter 注册表构建前调用，
 *       完成 PAPI 加载并提供 resolver。</li>
 *   <li>{@link #connectHandler(PluginConfig, IndicatorHandler, RapidTransientScheduler)} —
 *       在 {@link IndicatorHandler} 构建后调用，绑定 handler 并在 Bukkit 回退模式下注册轮询 daemon。</li>
 * </ol>
 *
 * <p>重载时调用 {@link #beginReload(PluginConfig, IndicatorHandler, RapidTransientScheduler)}。</p>
 */
public final class IntegrationManager {

    private final Plugin plugin;
    private PlaceholderAPIIntegration papiIntegration;
    private LuckPermsIntegration lpIntegration;
    /** 当前绑定的 IndicatorHandler，LP 事件回调与 daemon 共用。 */
    private IndicatorHandler handler;
    private RapidTransientScheduler.TaskHandle variantPollDaemon;

    // ── 初始化 ─────────────────────────────────────────────────────────────────

    /**
     * Phase 1：尝试加载 PAPI（若已启用且已安装）。
     * 必须在 {@link cn.warriorview.formatter.NumberFormatRegistry} 等注册表构建前调用。
     */
    public IntegrationManager(Plugin plugin, PluginConfig config) {
        this.plugin = plugin;
        if (config.integrations.placeholderapi.enabled) {
            this.papiIntegration = PlaceholderAPIIntegration.tryLoad(plugin);
        }
    }

    /** 重置 PAPI 解析会话，确保本次加载/重载期间缓存从头计算。无 PAPI 实例时为空操作。 */
    public void beginPapiSession() {
        if (papiIntegration != null) papiIntegration.beginResolveSession();
    }

    /** 返回 PAPI 解析器，供注册表构建时注入。PAPI 不可用时返回恒等函数。 */
    public UnaryOperator<String> getPapiResolver() {
        return papiIntegration != null ? papiIntegration.asResolver() : UnaryOperator.identity();
    }

    /** 返回 PAPI 集成实例（可能为 {@code null}）。 */
    public PlaceholderAPIIntegration getPapiIntegration() {
        return papiIntegration;
    }

    /**
     * Phase 2a：尝试加载 LuckPerms 并返回合适的权限检查器，供构建 {@link IndicatorHandler} 使用。
     *
     * <p>LP 的失效回调通过 {@code this.handler} 延迟绑定；
     * {@code handler} 将在 {@link #connectHandler} 中设置，届时 LP 事件尚未触发，安全。</p>
     *
     * @return LP 加载成功时返回高性能 checker；否则返回 {@link PermissionChecker#BUKKIT}
     */
    public PermissionChecker loadLuckPerms(PluginConfig config) {
        if (!config.integrations.luckperms.enabled) return PermissionChecker.BUKKIT;
        // lambda 通过 this.handler 延迟绑定；connectHandler() 中设置后才会有事件触发
        this.lpIntegration = LuckPermsIntegration.tryLoad(plugin,
                uuid -> { if (this.handler != null) this.handler.invalidateVariantCache(uuid); });
        if (lpIntegration != null) return lpIntegration.buildChecker();
        return PermissionChecker.BUKKIT;
    }

    /**
     * Phase 2b：绑定 {@link IndicatorHandler}，并在 Bukkit 回退模式下注册变体缓存轮询 daemon。
     * 必须在 {@link IndicatorHandler} 构建完成后调用。
     */
    public void connectHandler(PluginConfig config, IndicatorHandler handler,
                               RapidTransientScheduler scheduler) {
        this.handler = handler;
        if (lpIntegration == null) {
            long pollTicks = config.variants.permissionCacheRefreshTicks;
            this.variantPollDaemon = scheduler.dispatchDaemon(
                    handler::pollVariantCache, pollTicks, pollTicks);
        }
    }

    // ── 重载 ─────────────────────────────────────────────────────────────────

    /**
     * 重载时调用，统一处理集成状态更新与 daemon 重建：
     * <ul>
     *   <li>PAPI：已有实例则重置解析会话；首次发现已安装则懒加载
     *       （resolver 已烧入注册表，需重启才能完全生效）。</li>
     *   <li>LP：已有实例则跳过（事件已注册，checker 已生效）；
     *       首次发现已安装则加载并热替换 permChecker。</li>
     *   <li>daemon：LP 激活则取消旧 daemon；否则以最新配置值重建。</li>
     * </ul>
     */
    public void beginReload(PluginConfig config, IndicatorHandler handler,
                            RapidTransientScheduler scheduler) {
        this.handler = handler;

        // ── PAPI ─────────────────────────────────────────────────────────────
        if (papiIntegration != null) {
            papiIntegration.beginResolveSession();
        } else if (config.integrations.placeholderapi.enabled) {
            PlaceholderAPIIntegration loaded = PlaceholderAPIIntegration.tryLoad(plugin);
            if (loaded != null) {
                this.papiIntegration = loaded;
                Log.warn("[Integration] PlaceholderAPI became available on reload. "
                        + "Resolver is baked into registries — restart for full placeholder support.");
            }
        }

        // ── LP ───────────────────────────────────────────────────────────────
        if (lpIntegration == null && config.integrations.luckperms.enabled) {
            LuckPermsIntegration lp = LuckPermsIntegration.tryLoad(plugin, handler::invalidateVariantCache);
            if (lp != null) {
                this.lpIntegration = lp;
                handler.setPermissionChecker(lp.buildChecker());
                handler.clearVariantCache();
                Log.info("[Integration] LuckPerms integration activated on reload.");
            }
        }

        // ── daemon ───────────────────────────────────────────────────────────
        updateDaemon(config, scheduler);
    }

    // ── 内部工具 ───────────────────────────────────────────────────────────────

    private void updateDaemon(PluginConfig config, RapidTransientScheduler scheduler) {
        if (lpIntegration != null) {
            if (variantPollDaemon != null) { variantPollDaemon.cancel(); variantPollDaemon = null; }
            return;
        }
        long pollTicks = config.variants.permissionCacheRefreshTicks;
        if (variantPollDaemon != null) variantPollDaemon.cancel();
        variantPollDaemon = scheduler.dispatchDaemon(handler::pollVariantCache, pollTicks, pollTicks);
    }
}
