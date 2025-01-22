package cn.warriorView.Manager;

import cn.warriorView.Configuration.Config;
import cn.warriorView.Main;
import cn.warriorView.Object.Animation.Animation;
import cn.warriorView.Object.Range;
import cn.warriorView.Object.Replacement;
import cn.warriorView.Util.MathUtil;
import cn.warriorView.Util.RegistryUtil;
import cn.warriorView.Util.XLogger;
import cn.warriorView.View.DamageView.CriticalView;
import cn.warriorView.View.DamageView.DamageOtherView;
import cn.warriorView.View.DamageView.DamageView;
import cn.warriorView.View.DamageView.ProjectileView;
import cn.warriorView.View.RegainView.RegainView;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;

import java.io.File;
import java.util.Set;

public class ConfigManager {
    private final AnimationManager animationManager;
    private final ReplacementManager replacementManager;
    private final Main plugin;
    private ViewManager viewManager;

    public ConfigManager(Main main) {
        this.plugin = main;
        animationManager = new AnimationManager();
        replacementManager = new ReplacementManager();
        load();
    }

    public void load() {
        viewManager = plugin.getViewManager();
        viewManager.init();
        replacementManager.load();
        animationManager.load();
        loadDamageViewFile(YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), Config.damageEntity.apply)));
        loadRegainHealthFile(YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), Config.regainHealth.apply)));

    }

    private void loadDamageViewFile(FileConfiguration viewFile) {
        Set<String> topKeys = viewFile.getKeys(false);
        Config.DamageEntity.Defaults defaults = Config.DamageEntity.defaults;
        DamageView defaultView = new DamageView(
                defaults.textFormat,
                replacementManager.getReplacement(defaults.replacement),
                new Range(defaults.scale),
                defaults.shadow,
                defaults.viewRange,
                defaults.viewMarge,
                defaults.backgroundColor,
                defaults.seeThrough,
                defaults.onlyPlayer,
                animationManager.getAnimation(defaults.animation),
                defaults.position
        );
        for (String key : topKeys) {
            ConfigurationSection section = viewFile.getConfigurationSection(key);
            boolean isCritical = key.equalsIgnoreCase("CRITICAL");
            EntityDamageEvent.DamageCause cause = RegistryUtil.toDamageCause(key);
            if (cause == null) {
                if (!isCritical) {
                    XLogger.err("Invalid Damage Cause: " + key);
                    continue;
                }
            }
            if (section == null) {
                if (isCritical) {
                    CriticalView criticalView = new CriticalView(
                            defaults.textFormat,
                            replacementManager.getReplacement(defaults.replacement),
                            new Range(defaults.scale),
                            defaults.shadow,
                            defaults.viewRange,
                            defaults.viewMarge,
                            defaults.backgroundColor,
                            defaults.seeThrough,
                            defaults.onlyPlayer,
                            animationManager.getAnimation(defaults.animation),
                            defaults.position
                    );
                    viewManager.setCriticalView(criticalView);
                    continue;
                }
                viewManager.addDamageViews(cause, defaultView);
                continue;
            }
            String textFormat = section.getString("text-format", defaults.textFormat);
            Replacement replacement = replacementManager.getReplacement(section.getString("replacement", defaults.replacement));
            Range scale = new Range(section.getString("scale", defaults.scale));
            boolean shadow = section.getBoolean("shadow", defaults.shadow);
            float viewRange = MathUtil.round(section.getDouble("view-range", defaults.viewRange), 1);
            byte viewMarge = MathUtil.convertIntToByte(section.getInt("view-marge", defaults.viewMarge));
            int backgroundColor = section.getInt("background-color", defaults.backgroundColor);
            boolean seeThrough = section.getBoolean("see-through", defaults.onlyPlayer);
            boolean onlyPlayer = section.getBoolean("only-player", defaults.onlyPlayer);
            String position = section.getString("position", defaults.position);
            Animation animation = animationManager.getAnimation(section.getString("animation", defaults.animation));
            if (isCritical) {
                CriticalView criticalView = new CriticalView(
                        textFormat,
                        replacement,
                        scale,
                        shadow,
                        viewRange,
                        viewMarge,
                        backgroundColor,
                        seeThrough,
                        onlyPlayer,
                        animation,
                        position
                );
                viewManager.setCriticalView(criticalView);
                continue;
            }
            if (cause == EntityDamageEvent.DamageCause.PROJECTILE) {
                viewManager.addDamageViews(cause, new ProjectileView(
                        textFormat,
                        replacement,
                        scale,
                        shadow,
                        viewRange,
                        viewMarge,
                        backgroundColor,
                        seeThrough,
                        onlyPlayer,
                        animation,
                        position
                ));
            } else if (cause == EntityDamageEvent.DamageCause.ENTITY_ATTACK) {
                viewManager.addDamageViews(cause, new DamageOtherView(
                        textFormat,
                        replacement,
                        scale,
                        shadow,
                        viewRange,
                        viewMarge,
                        backgroundColor,
                        seeThrough,
                        onlyPlayer,
                        animation,
                        position
                ));
            } else {
                viewManager.addDamageViews(cause, new DamageView(
                        textFormat,
                        replacement,
                        scale,
                        shadow,
                        viewRange,
                        viewMarge,
                        backgroundColor,
                        seeThrough,
                        onlyPlayer,
                        animation,
                        position
                ));
            }
        }
    }

    private void loadRegainHealthFile(FileConfiguration viewFile) {
        Set<String> topKeys = viewFile.getKeys(false);
        Config.RegainHealth.Defaults defaults = Config.RegainHealth.defaults;
        DamageView defaultView = new DamageView(
                defaults.textFormat,
                replacementManager.getReplacement(defaults.replacement),
                new Range(defaults.scale),
                defaults.shadow,
                defaults.viewRange,
                defaults.viewMarge,
                defaults.backgroundColor,
                defaults.seeThrough,
                defaults.onlyPlayer,
                animationManager.getAnimation(defaults.animation),
                defaults.position
        );
        for (String key : topKeys) {
            ConfigurationSection section = viewFile.getConfigurationSection(key);
            EntityRegainHealthEvent.RegainReason reason = RegistryUtil.toRegainReason(key);
            if (reason == null) {
                XLogger.err("Invalid Regain reason: " + key);
                continue;
            }
            if (section == null) {
                viewManager.addRegainViews(reason, defaultView);
                continue;
            }
            String textFormat = section.getString("text-format", defaults.textFormat);
            Replacement replacement = replacementManager.getReplacement(section.getString("replacement", defaults.replacement));
            Range scale = new Range(section.getString("scale", defaults.scale));
            boolean shadow = section.getBoolean("shadow", defaults.shadow);
            float viewRange = MathUtil.round(section.getDouble("view-range", defaults.viewRange), 1);
            byte viewMarge = MathUtil.convertIntToByte(section.getInt("view-marge", defaults.viewMarge));
            int backgroundColor = section.getInt("background-color", defaults.backgroundColor);
            boolean seeThrough = section.getBoolean("see-through", defaults.onlyPlayer);
            boolean onlyPlayer = section.getBoolean("only-player", defaults.onlyPlayer);
            Animation animation = animationManager.getAnimation(section.getString("animation", defaults.animation));
            String position = section.getString("position", defaults.position);
            viewManager.addRegainViews(reason, new RegainView(
                    textFormat,
                    replacement,
                    scale,
                    shadow,
                    viewRange,
                    viewMarge,
                    backgroundColor,
                    seeThrough,
                    onlyPlayer,
                    animation,
                    position
            ));

        }
    }

}
