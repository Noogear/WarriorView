package cn.warriorview;

import cn.warriorview.api.WarriorView;
import cn.warriorview.api.WarriorViewAPI;
import cn.warriorview.api.manager.AnimationManager;
import cn.warriorview.api.manager.ScriptManager;
import cn.warriorview.command.WarriorViewCommand;
import cn.warriorview.configFile.AnimationConfig;
import cn.warriorview.configFile.IndicatorConfigLoader;
import cn.warriorview.configFile.MessageConfig;
import cn.warriorview.configFile.PluginConfig;
import cn.warriorview.integration.LuckPermsIntegration;
import cn.warriorview.integration.PermissionChecker;
import cn.warriorview.integration.PlaceholderAPIIntegration;
import cn.warriorview.listener.IndicatorHandler;
import cn.warriorview.manager.AnimationManagerImpl;
import cn.warriorview.manager.BukkitScriptManager;
import cn.warriorview.util.Log;
import gloomlib.configuration.api.ConfigurationManager;
import cn.warriorview.api.manager.CharReplaceManager;
import cn.warriorview.api.manager.NumberFormatManager;
import cn.warriorview.formatter.CharReplaceRegistry;
import cn.warriorview.formatter.NumberFormatRegistry;
import cn.warriorview.util.RapidTransientScheduler;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

import cn.warriorview.api.manager.IndicatorManager;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

public class Main extends JavaPlugin implements WarriorView {

    private AnimationManager animationManager;
    private BukkitScriptManager scriptManager;

    private RapidTransientScheduler animationScheduler;
    private AnimationConfig animationConfig;
    private NumberFormatRegistry numberFormatRegistry;
    private CharReplaceRegistry  charReplaceRegistry;
    private IndicatorConfigLoader indicatorConfigLoader;
    private PluginConfig pluginConfig;
    private MessageConfig messageConfig;
    private IndicatorHandler indicatorHandler;
    private PlaceholderAPIIntegration papiIntegration;
    private LuckPermsIntegration lpIntegration;
    private RapidTransientScheduler.TaskHandle variantPollDaemon;

    @Override
    public void onEnable() {
        // ── 全局日志初始化（最先执行） ──────────────────────────────────
        Log.init(this);
        // ── 插件全局配置 ───────────────────────────────────────────────
        try {
            this.pluginConfig = ConfigurationManager.load(PluginConfig.class,
                    new java.io.File(getDataFolder(), "config.yml"));
        } catch (Exception e) {
            Log.error("[PluginConfig] Failed to load: {}", e.getMessage());
            this.pluginConfig = new PluginConfig();
        }
        // ── 消息配置 ─────────────────────────────────────────────────────
        try {
            this.messageConfig = ConfigurationManager.load(MessageConfig.class,
                    new java.io.File(getDataFolder(), "message.yml"));
            this.messageConfig.refreshTimestamp();
        } catch (Exception e) {
            Log.error("[MessageConfig] Failed to load: {}", e.getMessage());
            this.messageConfig = new MessageConfig();
        }

        // ── 动画系统 ─────────────────────────────────────────────────────
        long t0 = System.currentTimeMillis();
        var sched = pluginConfig.scheduler;
        // wheel-size=64, pool-capacity=1024 为内部固定参数，不暴露给配置文件
        this.animationScheduler = new RapidTransientScheduler(this,
                64, 1024, sched.maxTasksPerTick, sched.idleThreshold, sched.backlogBurstMultiplier);
        this.animationConfig    = new AnimationConfig(this, animationScheduler);
        this.animationConfig.reload();
        if (pluginConfig.timingLog) Log.info("[Timing] animations loaded in {} ms", System.currentTimeMillis() - t0);

        // ── PlaceholderAPI 集成（加载期解析全局占位符）───────────────────
        UnaryOperator<String> papiResolver = UnaryOperator.identity();
        if (pluginConfig.integrations.placeholderapi.enabled) {
            this.papiIntegration = PlaceholderAPIIntegration.tryLoad(this);
            if (papiIntegration != null) {
                papiResolver = papiIntegration.asResolver();
            }
        }

        // ── 数字格式化注册表（必须先于 IndicatorConfigLoader）────────────────────
        t0 = System.currentTimeMillis();
        if (papiIntegration != null) papiIntegration.beginResolveSession();
        this.numberFormatRegistry = new NumberFormatRegistry(this, papiResolver);
        this.numberFormatRegistry.reload();
        this.charReplaceRegistry  = new CharReplaceRegistry(this, papiResolver);
        this.charReplaceRegistry.reload();
        if (pluginConfig.timingLog) Log.info("[Timing] formatters loaded in {} ms", System.currentTimeMillis() - t0);

        // ── 指示器 Action 配置（tag → TextDisplay 参数 + 生成策略） ──────
        t0 = System.currentTimeMillis();
        this.indicatorConfigLoader = new IndicatorConfigLoader(
                this, animationConfig.getRegistry(), numberFormatRegistry, charReplaceRegistry, papiResolver);
        this.indicatorConfigLoader.load();
        if (pluginConfig.timingLog) Log.info("[Timing] indicators loaded in {} ms", System.currentTimeMillis() - t0);

        // ── 指示器引擎（事件驱动异步处理）────────────────────────────────
        // ── LuckPerms 集成，为指示器变体提供高性能权限检查 ──────────────
        PermissionChecker permChecker = PermissionChecker.BUKKIT;
        boolean lpLoaded = false;
        if (pluginConfig.integrations.luckperms.enabled) {
            // LP 事件在服务器运行期间触发，indicatorHandler 届时已初始化
            this.lpIntegration = LuckPermsIntegration.tryLoad(
                    this, uuid -> indicatorHandler.invalidateVariantCache(uuid));
            if (lpIntegration != null) {
                permChecker = lpIntegration.buildChecker();
                lpLoaded = true;
            }
        }
        this.indicatorHandler = new IndicatorHandler(
                animationConfig.getPlayer(),
                indicatorConfigLoader,
                animationScheduler,
                permChecker);
        // Bukkit 回退：无事件驱动失效，注册守护任务按 permissionCacheRefreshTicks（默认 200）轮询刷新变体缓存
        if (!lpLoaded) {
            long pollTicks = pluginConfig.variants.permissionCacheRefreshTicks;
            this.variantPollDaemon = animationScheduler.dispatchDaemon(
                    indicatorHandler::pollVariantCache, pollTicks, pollTicks);
        }
        getServer().getPluginManager().registerEvents(indicatorHandler, this);

        // ── 脚本系统（在动画系统就绪后初始化，action 需要 API） ─────────
        this.scriptManager = new BukkitScriptManager(this);

        // ── API 门面 ─────────────────────────────────────────
        this.animationManager = new AnimationManagerImpl(animationConfig, indicatorHandler);

        WarriorViewAPI.register(this);
        WarriorViewCommand.register(this);

        // ── 延迟加载：脚本 + PAPI 未解析占位符重试 ─────────────────────────
        final PlaceholderAPIIntegration papiRef = this.papiIntegration;
        getServer().getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onServerLoad(ServerLoadEvent event) {
                long scriptT0 = System.currentTimeMillis();
                scriptManager.reloadScripts();
                if (pluginConfig.timingLog) Log.info("[Timing] scripts loaded in {} ms", System.currentTimeMillis() - scriptT0);

                if (papiRef != null && papiRef.hasUnresolvedPlaceholders()) {
                    Log.info("[PlaceholderAPI] Retrying unresolved placeholders after server load...");
                    papiRef.beginResolveSession();
                    long papiT0 = System.currentTimeMillis();
                    numberFormatRegistry.reload();
                    charReplaceRegistry.reload();
                    indicatorConfigLoader.load();
                    indicatorHandler.clearVariantCache();
                    if (pluginConfig.timingLog) Log.info("[Timing] PAPI retry reload in {} ms", System.currentTimeMillis() - papiT0);
                    if (papiRef.hasUnresolvedPlaceholders()) {
                        Log.warn("[PlaceholderAPI] Some placeholders remain unresolved after retry. "
                                + "Check if the required PAPI expansions are installed.");
                    }
                }
            }
        }, this);

        Log.info("WarriorView v{} 已启用", getVersion());
    }

    @Override
    public void onDisable() {
        if (animationScheduler != null) animationScheduler.shutdown();
        if (scriptManager != null)      scriptManager.unloadScripts();
        WarriorViewAPI.unregister();
        Log.info("WarriorView 已禁用");
    }

    // ── WarriorView 接口 ────────────────────────────────────────────────

    @Override public AnimationManager    getAnimationManager()    { return animationManager; }
    @Override public ScriptManager       getScriptManager()       { return scriptManager; }
    @Override public IndicatorManager    getIndicatorManager()    { return indicatorConfigLoader; }
    @Override public NumberFormatManager getNumberFormatManager() { return numberFormatRegistry; }
    @Override public CharReplaceManager  getCharReplaceManager()  { return charReplaceRegistry; }
    @Override public String              getVersion()             { return getPluginMeta().getVersion(); }

    private void reloadPluginConfig() {
        try {
            this.pluginConfig = ConfigurationManager.load(PluginConfig.class,
                    new java.io.File(getDataFolder(), "config.yml"));
        } catch (Exception e) {
            Log.error("[PluginConfig] Failed to reload: {}", e.getMessage());
        }
    }

    /**
     * 智能集成重载：
     * <ul>
     *   <li>PAPI：已有实例则重置解析会话；首次发现 PAPI 已安装则尝试加载（resolver 已烧入注册表，需重启方可完整生效）。</li>
     *   <li>LP：已有实例则跳过（事件已注册，checker 已生效）；首次发现 LP 已安装则加载并热替换 permChecker。</li>
     * </ul>
     */
    private void reloadIntegrations() {
        // ── PAPI ──────────────────────────────────────────────────────────────
        if (papiIntegration != null) {
            papiIntegration.beginResolveSession();
        } else if (pluginConfig.integrations.placeholderapi.enabled) {
            PlaceholderAPIIntegration loaded = PlaceholderAPIIntegration.tryLoad(this);
            if (loaded != null) {
                this.papiIntegration = loaded;
                Log.warn("[Integration] PlaceholderAPI became available on reload. "
                        + "Resolver is baked into registries — restart for full placeholder support.");
            }
        }
        // ── LP ────────────────────────────────────────────────────────────────
        if (lpIntegration != null) return; // 已加载：事件已注册，checker 已生效，无需重复
        if (!pluginConfig.integrations.luckperms.enabled) return;
        LuckPermsIntegration lp = LuckPermsIntegration.tryLoad(
                this, uuid -> indicatorHandler.invalidateVariantCache(uuid));
        if (lp != null) {
            this.lpIntegration = lp;
            indicatorHandler.setPermissionChecker(lp.buildChecker());
            indicatorHandler.clearVariantCache();
            // LP 激活后不再需要轮询 daemon，在 reloadVariantPollDaemon 中统一取消
            Log.info("[Integration] LuckPerms integration activated on reload.");
        }
    }

    /**
     * 根据当前集成状态维护 Bukkit 轮询 daemon：
     * LP 已激活时取消旧 daemon；否则以最新配置值取消旧 daemon 并重新注册。
     */
    private void reloadVariantPollDaemon() {
        if (lpIntegration != null) {
            if (variantPollDaemon != null) { variantPollDaemon.cancel(); variantPollDaemon = null; }
            return;
        }
        long pollTicks = pluginConfig.variants.permissionCacheRefreshTicks;
        if (variantPollDaemon != null) variantPollDaemon.cancel();
        variantPollDaemon = animationScheduler.dispatchDaemon(
                indicatorHandler::pollVariantCache, pollTicks, pollTicks);
    }

    @Override
    public void reloadAll() {
        reloadPluginConfig();
        reloadIntegrations();
        reloadVariantPollDaemon();
        long t0, total = System.currentTimeMillis();
        boolean timing = pluginConfig.timingLog;

        t0 = System.currentTimeMillis();
        animationManager.reload();
        if (timing) Log.info("[Timing] animations reloaded in {} ms", System.currentTimeMillis() - t0);

        t0 = System.currentTimeMillis();
        numberFormatRegistry.reload();
        charReplaceRegistry.reload();
        if (timing) Log.info("[Timing] formatters reloaded in {} ms", System.currentTimeMillis() - t0);

        t0 = System.currentTimeMillis();
        indicatorConfigLoader.load();
        indicatorHandler.clearVariantCache();
        if (timing) Log.info("[Timing] indicators reloaded in {} ms", System.currentTimeMillis() - t0);

        t0 = System.currentTimeMillis();
        scriptManager.reloadScripts();
        if (timing) Log.info("[Timing] scripts reloaded in {} ms", System.currentTimeMillis() - t0);

        if (timing) Log.info("[Timing] reloadAll completed in {} ms", System.currentTimeMillis() - total);
    }

    @Override
    public Map<String, Boolean> smartReloadAll() {
        reloadPluginConfig();
        reloadIntegrations();
        reloadVariantPollDaemon();
        Map<String, Boolean> result = new LinkedHashMap<>();
        long t0, total = System.currentTimeMillis();
        boolean timing = pluginConfig.timingLog;

        t0 = System.currentTimeMillis();
        boolean animChanged = animationConfig.hasChanges();
        if (animChanged) animationConfig.reload();
        result.put("animations", animChanged);
        if (timing) Log.info("[Timing] animations smart-reloaded in {} ms (changed={})",
                System.currentTimeMillis() - t0, animChanged);

        t0 = System.currentTimeMillis();
        boolean nfChanged = numberFormatRegistry.smartReload();
        boolean crChanged = charReplaceRegistry.smartReload();
        result.put("formatters", nfChanged || crChanged);
        if (timing) Log.info("[Timing] formatters smart-reloaded in {} ms (changed={})",
                System.currentTimeMillis() - t0, nfChanged || crChanged);

        t0 = System.currentTimeMillis();
        boolean indicatorChanged;
        if (animChanged || nfChanged || crChanged) {
            indicatorConfigLoader.load();
            indicatorHandler.clearVariantCache();
            indicatorChanged = true;
        } else {
            indicatorChanged = indicatorConfigLoader.smartReload();
            if (indicatorChanged) indicatorHandler.clearVariantCache();
        }
        result.put("indicators", indicatorChanged);
        if (timing) Log.info("[Timing] indicators smart-reloaded in {} ms (changed={})",
                System.currentTimeMillis() - t0, indicatorChanged);

        t0 = System.currentTimeMillis();
        scriptManager.reloadScripts();
        result.put("scripts", true);
        if (timing) Log.info("[Timing] scripts smart-reloaded in {} ms", System.currentTimeMillis() - t0);

        if (timing) Log.info("[Timing] smartReloadAll completed in {} ms", System.currentTimeMillis() - total);
        return result;
    }

    // ── Core 内部访问 ───────────────────────────────────────────────────

    public MessageConfig getMessageConfig() { return messageConfig; }
    public PluginConfig    getPluginConfig()  { return pluginConfig; }
}
