package cn.warriorView.Manager;

import cn.warriorView.Animation.Animation;
import cn.warriorView.Animation.AnimationParams;
import cn.warriorView.Configuration.Config;
import cn.warriorView.Main;
import cn.warriorView.Object.Range;
import cn.warriorView.Util.ConfigFile.ConfigurationManager;
import cn.warriorView.Util.MathUtil;
import cn.warriorView.Util.RegistryUtil;
import cn.warriorView.Util.XLogger;
import cn.warriorView.View.ViewParams;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;

import java.io.File;
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
        replacementManager.load(new File(plugin.getDataFolder(), Config.replacement));
        animationManager.load(new File(plugin.getDataFolder(), Config.animation));
        loadDamageViewFile(YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), Config.damageEntity.apply)));
        loadRegainHealthFile(YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), Config.regainHealth.apply)));

    }

    private void loadDamageViewFile(FileConfiguration viewFile) {
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
    }

    private void loadRegainHealthFile(FileConfiguration viewFile) {
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
    }


    private ViewParams buildDamageViewParams(ConfigurationSection section) {
        Config.DamageEntity.Defaults defaults = Config.DamageEntity.defaults;
        return getViewParams(section, defaults.textFormat, defaults.replacement, defaults.scale, defaults.shadow, defaults.viewRange, defaults.viewMarge, defaults.backgroundColor, defaults.seeThrough, defaults.onlyPlayer, defaults.animation, defaults.position, defaults.moveCount, defaults.delay);
    }

    private ViewParams buildRegainViewParams(ConfigurationSection section) {
        Config.RegainHealth.Defaults defaults = Config.RegainHealth.defaults;
        return getViewParams(section, defaults.textFormat, defaults.replacement, defaults.scale, defaults.shadow, defaults.viewRange, defaults.viewMarge, defaults.backgroundColor, defaults.seeThrough, defaults.onlyPlayer, defaults.animation, defaults.position, defaults.moveCount, defaults.delay);
    }

    private ViewParams getViewParams(ConfigurationSection section, String textFormat, String replacement, String scale, boolean shadow, float viewRange, byte viewMarge, int backgroundColor, boolean seeThrough, boolean onlyPlayer, String animation, String position, byte moveCount, long delay) {
        if (section == null) {
            AnimationParams animParams = animationManager.getAnimation(animation);
            return new ViewParams(
                    textFormat,
                    replacementManager.getReplacement(replacement),
                    new Range(scale),
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
                new Range(section.getString("scale", scale)),
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
}
