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
import cn.warriorview.integration.IntegrationManager;
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
import java.util.concurrent.atomic.AtomicBoolean;
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
    private IntegrationManager integrationManager;
    private final AtomicBoolean reloading = new AtomicBoolean(false);

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

        // ── 集成管理器 Phase 1：PAPI 加载 ─────────────────────────────────────
        this.integrationManager = new IntegrationManager(this, pluginConfig);

        // ── 数字格式化注册表（必须先于 IndicatorConfigLoader）────────────────────
        t0 = System.currentTimeMillis();
        integrationManager.beginPapiSession();
        UnaryOperator<String> papiResolver = integrationManager.getPapiResolver();
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

        // ── 集成管理器 Phase 2a：LP 加载，获取权限检查器 ─────────────────────
        var permChecker = integrationManager.loadLuckPerms(pluginConfig);
        // ── 指示器引擎（事件驱动异步处理）────────────────────────────────
        this.indicatorHandler = new IndicatorHandler(
                animationConfig.getPlayer(),
                indicatorConfigLoader,
                animationScheduler,
                permChecker);
        // Phase 2b：绑定 handler，Bukkit 回退下注册轮询 daemon
        integrationManager.connectHandler(pluginConfig, indicatorHandler, animationScheduler);
        getServer().getPluginManager().registerEvents(indicatorHandler, this);

        // ── 脚本系统（在动画系统就绪后初始化，action 需要 API） ─────────
        this.scriptManager = new BukkitScriptManager(this);

        // ── API 门面 ─────────────────────────────────────────
        this.animationManager = new AnimationManagerImpl(animationConfig, indicatorHandler);

        WarriorViewAPI.register(this);
        WarriorViewCommand.register(this);

        // ── 延迟加载：脚本 + PAPI 未解析占位符重试 ─────────────────────────
        var papiRef = integrationManager.getPapiIntegration();
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

    @Override
    public void reloadAll() {
        if (!reloading.compareAndSet(false, true)) {
            Log.warn("[Reload] A reload is already in progress, skipping.");
            return;
        }
        try {
            reloadPluginConfig();
            integrationManager.beginReload(pluginConfig, indicatorHandler, animationScheduler);
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
        } finally {
            reloading.set(false);
        }
    }

    @Override
    public Map<String, Boolean> smartReloadAll() {
        if (!reloading.compareAndSet(false, true)) {
            Log.warn("[Reload] A reload is already in progress, skipping.");
            return Map.of();
        }
        try {
            reloadPluginConfig();
            integrationManager.beginReload(pluginConfig, indicatorHandler, animationScheduler);
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
        } finally {
            reloading.set(false);
        }
    }

    // ── Core 内部访问 ───────────────────────────────────────────────────

    public MessageConfig getMessageConfig() { return messageConfig; }
    public PluginConfig    getPluginConfig()  { return pluginConfig; }
}
