package cn.warriorView.Manager;

import cn.warriorView.Animation.Animation;
import cn.warriorView.Animation.AnimationParams;
import cn.warriorView.Configuration.Config;
import cn.warriorView.Main;
import cn.warriorView.Object.Scale;
import cn.warriorView.Util.ConfigFile.ConfigurationManager;
import cn.warriorView.Util.MathUtil;
import cn.warriorView.Util.RegistryUtil;
import cn.warriorView.Util.XLogger;
import cn.warriorView.View.ViewParams;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Set;

public class ConfigManager {
    private final File configFile;
    private final AnimationManager animationManager;
    private final ReplacementManager replacementManager;
    private final Main plugin;
    private final ViewManager viewManager;

    public ConfigManager(Main main) {
        this.plugin = main;
        animationManager = new AnimationManager();
        replacementManager = new ReplacementManager();
        viewManager = plugin.getViewManager();
        configFile = new File(plugin.getDataFolder(), "config.yml");
        load();
    }

    public void load() {
        try {
            ConfigurationManager.load(Config.class, configFile, "version");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        viewManager.init();
        animationManager.init();
        replacementManager.init();
        if (!Config.enabled) return;
        replacementManager.load(loadOrCreateConfig(Config.replacement, "replacement.yml"));
        animationManager.load(loadOrCreateConfig(Config.animation, "animation.yml"));
        loadDamageView(loadOrCreateConfig(Config.damageEntity.apply, "damage_cause.yml"));
        loadRegainHealth(loadOrCreateConfig(Config.regainHealth.apply, "regain_reason.yml"));

    }

    private void loadDamageView(YamlConfiguration viewFile) {
        Set<String> topKeys = viewFile.getKeys(false);
        for (String key : topKeys) {
            ConfigurationSection section = viewFile.getConfigurationSection(key);
            boolean isCritical = "CRITICAL".equalsIgnoreCase(key);
            EntityDamageEvent.DamageCause cause = RegistryUtil.toDamageCause(key);
            if (cause == null) {
                if (!isCritical) {
                    XLogger.err("Invalid Damage Cause: " + key);
                    continue;
                }
            }
            ViewParams viewParams = buildDamageViewParams(section);
            if (isCritical) {
                viewManager.setCriticalView(viewParams);
                continue;
            }
            viewManager.addDamageViews(cause, viewParams);
        }
        XLogger.info("Successfully load " + viewManager.getDamageViews().size() + " damage view(s)");
    }

    private void loadRegainHealth(YamlConfiguration viewFile) {
        Set<String> topKeys = viewFile.getKeys(false);
        for (String key : topKeys) {
            ConfigurationSection section = viewFile.getConfigurationSection(key);
            EntityRegainHealthEvent.RegainReason reason = RegistryUtil.toRegainReason(key);
            if (reason == null) {
                XLogger.err("Invalid Regain reason: " + key);
                continue;
            }
            ViewParams viewParams = buildRegainViewParams(section);
            viewManager.addRegainViews(reason, viewParams);
        }
        XLogger.info("Successfully load " + viewManager.getRegainViews().size() + " regain view(s)");
    }


    private ViewParams buildDamageViewParams(ConfigurationSection section) {
        Config.DamageEntity.Defaults defaults = Config.DamageEntity.defaults;
        return getViewParams(
                section,
                defaults.textFormat,
                defaults.replacement,
                defaults.scale,
                defaults.shadow,
                MathUtil.round(defaults.viewRange, 2),
                MathUtil.convertIntToByte(defaults.viewMarge),
                defaults.backgroundColor,
                defaults.seeThrough,
                defaults.onlyPlayer,
                defaults.animation,
                defaults.position,
                MathUtil.convertIntToByte(defaults.moveCount),
                defaults.delay
        );
    }

    private ViewParams buildRegainViewParams(ConfigurationSection section) {
        Config.RegainHealth.Defaults defaults = Config.RegainHealth.defaults;
        return getViewParams(
                section,
                defaults.textFormat,
                defaults.replacement,
                defaults.scale,
                defaults.shadow,
                MathUtil.round(defaults.viewRange, 2),
                MathUtil.convertIntToByte(defaults.viewMarge),
                defaults.backgroundColor,
                defaults.seeThrough,
                defaults.onlyPlayer,
                defaults.animation,
                defaults.position,
                MathUtil.convertIntToByte(defaults.moveCount),
                defaults.delay);
    }

    private ViewParams getViewParams(ConfigurationSection section, String textFormat, String replacement, String scale, boolean shadow, float viewRange, byte viewMarge, int backgroundColor, boolean seeThrough, boolean onlyPlayer, String animation, String position, byte moveCount, long delay) {
        if (section == null) {
            AnimationParams animParams = animationManager.getAnimation(animation);
            return new ViewParams(
                    textFormat,
                    replacementManager.getReplacement(replacement),
                    new Scale(scale),
                    shadow,
                    viewRange,
                    viewMarge,
                    backgroundColor,
                    seeThrough,
                    onlyPlayer,
                    Animation.build(animParams, moveCount, delay),
                    position
            );
        }
        AnimationParams animParams = animationManager.getAnimation(section.getString("animation", animation));
        return new ViewParams(
                section.getString("text-format", textFormat),
                replacementManager.getReplacement(section.getString("replacement", replacement)),
                new Scale(section.getString("scale", scale)),
                section.getBoolean("shadow", shadow),
                MathUtil.round(section.getDouble("view-range", viewRange), 1),
                MathUtil.convertIntToByte(section.getInt("view-marge", viewMarge)),
                section.getInt("background-color", backgroundColor),
                section.getBoolean("see-through", seeThrough),
                section.getBoolean("only-player", onlyPlayer),
                Animation.build(animParams, MathUtil.convertIntToByte(section.getInt("move-count", moveCount)), section.getLong("delay", delay)),
                section.getString("position", position)
        );
    }

    /**
     * 加载或创建配置文件并返回配置对象
     *
     * @param targetFileName 要加载/创建的文件名（如 "2.yml"）
     * @param sourceResource JAR内源文件路径（如 "1.yml"）
     * @return 加载后的 FileConfiguration 对象
     */
    public YamlConfiguration loadOrCreateConfig(String targetFileName, String sourceResource) {
        File configFile = new File(plugin.getDataFolder(), targetFileName);
        try {
            // 确保插件数据目录存在
            if (!configFile.getParentFile().exists()) {
                configFile.getParentFile().mkdirs();
            }
            // 如果文件不存在，尝试从JAR复制
            if (!configFile.exists()) {
                try (InputStream input = plugin.getResource(sourceResource)) {
                    if (input != null) {
                        Files.copy(
                                input,
                                configFile.toPath(),
                                StandardCopyOption.REPLACE_EXISTING
                        );
                        XLogger.info("已从资源文件创建: " + targetFileName);
                    } else {
                        // 如果资源也不存在，生成空白配置
                        XLogger.warn("JAR内未找到资源，已生成空白配置: " + targetFileName);
                        return new YamlConfiguration();
                    }
                }
            }
            // 加载并返回配置
            return YamlConfiguration.loadConfiguration(configFile);
        } catch (IOException e) {
            XLogger.err("配置文件处理失败: " + e.getMessage());
            // 应急返回空配置
            return new YamlConfiguration();
        }
    }

}
