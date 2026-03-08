package cn.warriorview.configFile;

import cn.warriorview.animation.registry.AnimationRegistry;
import cn.warriorview.formatter.CharReplaceRegistry;
import cn.warriorview.formatter.NumberFormatRegistry;
import cn.warriorview.util.Log;

import gloomlib.configuration.api.ConfigurationManager;
import gloomlib.configuration.api.DirectoryConfiguration;

import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.Map;

/**
 * 从 {@code plugins/StrikeView/indicator/} 目录加载指示器配置。
 *
 * <p>委托给 {@link DirectoryConfiguration}，每个 YAML 文件的顶层键为 tag 名称
 * （如 {@code ENTITY_ATTACK}、{@code CRITICAL}），值自动反序列化为
 * {@link IndicatorConfig}。{@code default} 键由 {@code @Template} 保证存在。</p>
 */
public final class IndicatorConfigLoader {

    private final DirectoryConfiguration<IndicatorConfig> config;
    private boolean loaded;

    public IndicatorConfigLoader(Plugin plugin,
                                 AnimationRegistry   animationRegistry,
                                 NumberFormatRegistry numberFormatRegistry,
                                 CharReplaceRegistry  charReplaceRegistry) {
        this.config = ConfigurationManager.loadDirectory(
                        IndicatorConfig.class,
                        new File(plugin.getDataFolder(), "indicator"),
                        plugin::getResource)
                .withContext(new IndicatorContext(
                        animationRegistry, numberFormatRegistry, charReplaceRegistry));
    }

    /** 首次加载（全量读取）。 */
    public void load() {
        try {
            config.load();
            loaded = true;
        } catch (Exception e) {
            Log.error("[IndicatorConfig] Failed to load: {}", e.getMessage());
        }
    }

    /**
     * 智能重载：仅重新解析磁盘上已变更的文件，未变更的沿用上次结果。
     *
     * @return {@code true} 如果有文件发生了变更并重新加载
     */
    public boolean smartReload() {
        if (!loaded) { load(); return true; }
        try {
            return config.reload();
        } catch (Exception e) {
            Log.error("[IndicatorConfig] Failed to reload: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 根据 tag 获取配置，找不到时回退到 {@code default}。
     */
    public IndicatorConfig get(String tag) {
        return config.getOrDefault(tag);
    }

    /** 获取 {@code default} 配置项。 */
    public IndicatorConfig fallback() {
        return config.getOrDefault(null);
    }

    /** 返回所有已注册的指示器 tag 名称（只读快照）。 */
    public java.util.Collection<String> getTags() {
        return config.all().keySet();
    }

    /** 返回所有已加载的 tag 集合（只读）。 */
    public Map<String, IndicatorConfig> all() {
        return config.all();
    }
}
