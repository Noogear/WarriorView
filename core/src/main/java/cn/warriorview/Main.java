package cn.warriorview;

import cn.warriorview.api.WarriorView;
import cn.warriorview.api.WarriorViewAPI;
import cn.warriorview.api.manager.AnimationManager;
import cn.warriorview.api.manager.ScriptManager;
import cn.warriorview.command.WarriorViewCommand;
import cn.warriorview.configFile.AnimationConfig;
import cn.warriorview.configFile.IndicatorConfigLoader;
import cn.warriorview.configFile.MessageConfig;
import cn.warriorview.listener.DamageHandler;
import cn.warriorview.manager.AnimationManagerImpl;
import cn.warriorview.manager.BukkitScriptManager;
import cn.warriorview.util.Log;
import gloomlib.configuration.api.ConfigurationManager;
import cn.warriorview.api.manager.CharReplaceManager;
import cn.warriorview.api.manager.NumberFormatManager;
import cn.warriorview.formatter.CharReplaceRegistry;
import cn.warriorview.formatter.NumberFormatRegistry;
import cn.warriorview.util.RapidTransientScheduler;

import org.bukkit.plugin.java.JavaPlugin;

public class Main extends JavaPlugin implements WarriorView {

    private AnimationManager animationManager;
    private BukkitScriptManager scriptManager;

    private RapidTransientScheduler animationScheduler;
    private AnimationConfig animationConfig;
    private NumberFormatRegistry numberFormatRegistry;
    private CharReplaceRegistry  charReplaceRegistry;
    private IndicatorConfigLoader indicatorConfigLoader;
    private MessageConfig messageConfig;
    private DamageHandler damageHandler;

    @Override
    public void onEnable() {
        // ── 全局日志初始化（最先执行） ──────────────────────────────────
        Log.init(this);

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
        this.animationScheduler = new RapidTransientScheduler(this);
        this.animationConfig    = new AnimationConfig(this, animationScheduler);
        this.animationConfig.reload();

        // ── 数字格式化注册表（必须先于 IndicatorConfigLoader） ────────────────────
        this.numberFormatRegistry = new NumberFormatRegistry(this);
        this.numberFormatRegistry.reload();
        this.charReplaceRegistry  = new CharReplaceRegistry(this);
        this.charReplaceRegistry.reload();

        // ── 指示器 Action 配置（tag → TextDisplay 参数 + 生成策略） ──────
        this.indicatorConfigLoader = new IndicatorConfigLoader(
                this, animationConfig.getRegistry(), numberFormatRegistry, charReplaceRegistry);
        this.indicatorConfigLoader.load();

        // ── 伤害指示器（仅负责 quit 清理 + 引擎 tick，事件绑定由脚本驱动）──
        this.damageHandler = new DamageHandler(
                animationConfig.getPlayer(),
                indicatorConfigLoader);
        getServer().getPluginManager().registerEvents(damageHandler, this);
        animationScheduler.dispatchTimer(damageHandler::engineTick, 1L, 1L);

        // ── 脚本系统（在动画系统就绪后初始化，action 需要 API） ─────────
        this.scriptManager = new BukkitScriptManager(this);

        // ── API 门面 ─────────────────────────────────────────────────────
        this.animationManager = new AnimationManagerImpl(
                animationConfig, damageHandler, scriptManager, indicatorConfigLoader,
                numberFormatRegistry, charReplaceRegistry);

        // 先注册 API，再加载脚本（脚本 action 可能调用 API）
        WarriorViewAPI.register(this);
        WarriorViewCommand.register(this);
        this.scriptManager.reloadScripts();

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
    @Override public NumberFormatManager getNumberFormatManager() { return numberFormatRegistry; }
    @Override public CharReplaceManager  getCharReplaceManager()  { return charReplaceRegistry; }
    @Override public String              getVersion()             { return getPluginMeta().getVersion(); }

    // ── Core 内部访问 ───────────────────────────────────────────────────

    public MessageConfig getMessageConfig() { return messageConfig; }
}
