package cn.warriorView.Manager;

import cn.warriorView.Configuration.File.Config;
import cn.warriorView.Configuration.File.Language;
import cn.warriorView.Configuration.Form.ConfigurationManager;
import cn.warriorView.Main;
import cn.warriorView.Object.Animation.Animation;
import cn.warriorView.Object.Animation.AnimationParams;
import cn.warriorView.Object.Scale;
import cn.warriorView.Object.TextFormat.TextQuantize;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;

public class ConfigManager {
    private final File configFile;
    private final File languageFile;
    private final AnimationManager animationManager;
    private final TextFormatManager textFormatManager;
    private final Main plugin;
    private final ViewManager viewManager;

    public ConfigManager(Main main) {
        this.plugin = main;
        animationManager = new AnimationManager();
        textFormatManager = new TextFormatManager();
        viewManager = plugin.getViewManager();
        configFile = new File(plugin.getDataFolder(), "config.yml");
        languageFile = new File(plugin.getDataFolder(), "language.yml");
        load();
    }

    public void load() {
        try {
            ConfigurationManager.load(Config.class, configFile, "version");
            ConfigurationManager.load(Language.class, languageFile, "version");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        viewManager.init();
        animationManager.init();
        textFormatManager.init();
        if (!Config.enabled) {
            XLogger.info("WarriorView is not enabled!");
            return;
        }
        textFormatManager.load(loadOrCreateConfig(Config.replacement, "replacement.yml"));
        animationManager.load(loadOrCreateConfig(Config.animation, "animation.yml"));
        loadDamageView(loadOrCreateConfig(Config.damageEntity.apply, "views/damage_cause.yml"));
        loadRegainHealth(loadOrCreateConfig(Config.regainHealth.apply, "views/regain_reason.yml"));

    }

    private void loadDamageView(YamlConfiguration viewFile) {
        try{
            Set<String> topKeys = viewFile.getKeys(false);
            for (String key : topKeys) {
                ConfigurationSection section = viewFile.getConfigurationSection(key);
                boolean isCritical = "CRITICAL".equalsIgnoreCase(key);
                EntityDamageEvent.DamageCause cause = RegistryUtil.toDamageCause(key);
                if (cause == null) {
                    if (!isCritical) {
                        XLogger.err("Invalid Damage Cause: %s", key);
                        continue;
                    }
                }
                ViewParams viewParams = buildDamageViewParams(section);
                if (isCritical) {
                    viewManager.setCriticalView(viewParams);
                    XLogger.info("Successfully load critical view");
                    continue;
                }
                viewManager.addDamageViews(cause, viewParams);
            }
            XLogger.info("Successfully load %s damage view(s)", viewManager.getDamageViews().size());
        } catch (Exception e) {
            XLogger.err("Failed to load damage views: %s", e);
        }
    }

    private void loadRegainHealth(YamlConfiguration viewFile) {
        try{
            Set<String> topKeys = viewFile.getKeys(false);
            for (String key : topKeys) {
                ConfigurationSection section = viewFile.getConfigurationSection(key);
                EntityRegainHealthEvent.RegainReason reason = RegistryUtil.toRegainReason(key);
                if (reason == null) {
                    XLogger.err("Invalid regain reason: %s", key);
                    continue;
                }
                ViewParams viewParams = buildRegainViewParams(section);
                viewManager.addRegainViews(reason, viewParams);
            }
            XLogger.info("Successfully load %s regain view(s)", viewManager.getRegainViews().size());
        } catch (Exception e) {
            XLogger.err("Failed to load regain views: %s", e);
        }

    }


    private ViewParams buildDamageViewParams(ConfigurationSection section) {
        Config.DamageEntity.Defaults defaults = Config.DamageEntity.defaults;
        return getViewParams(
                section,
                defaults.textFormat,
                defaults.replacement,
                defaults.scale,
                defaults.shadow,
                defaults.opacity,
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
                defaults.opacity,
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

    private ViewParams getViewParams(ConfigurationSection section, String textFormat, String replacement, String scale, boolean shadow, double opacity, float viewRange, byte viewMarge, int backgroundColor, boolean seeThrough, boolean onlyPlayer, String animation, String position, byte moveCount, long delay) {
        if (section == null) {
            AnimationParams animParams = animationManager.getAnimation(animation);
            if("DAMAGE".equalsIgnoreCase(position)){
                throw new RuntimeException("The default config cannot use \"damage\" as the position.");
            }
            return new ViewParams(
                    TextQuantize.build(textFormat,) ,
                    textFormatManager.getReplacement(replacement),
                    Scale.create(scale),
                    shadow,
                    MathUtil.opacityFromPercent(opacity),
                    viewRange,
                    viewMarge,
                    backgroundColor,
                    seeThrough,
                    onlyPlayer,
                    Animation.create(animParams, moveCount, delay),
                    position
            );
        }
        AnimationParams animParams = animationManager.getAnimation(section.getString("animation", animation));
        return new ViewParams(
                textFormat,
                textFormatManager.getReplacement(section.getString("replacement", replacement)),
                Scale.create(section.getString("scale", scale)),
                section.getBoolean("shadow", shadow),
                MathUtil.opacityFromPercent(section.getDouble("opacity", opacity)),
                MathUtil.round(section.getDouble("view-range", viewRange), 1),
                MathUtil.convertIntToByte(section.getInt("view-marge", viewMarge)),
                section.getInt("background-color", backgroundColor),
                section.getBoolean("see-through", seeThrough),
                section.getBoolean("only-player", onlyPlayer),
                Animation.create(animParams, MathUtil.convertIntToByte(section.getInt("move-count", moveCount)), section.getLong("delay", delay)),
                section.getString("position", position)
        );
    }


    public YamlConfiguration loadOrCreateConfig(String targetFileName, String sourceResource) throws ConfigLoadingException {
        Path configFilePath = plugin.getDataFolder().toPath().resolve(targetFileName);
        Path parentDir = configFilePath.getParent();

        try {
            Files.createDirectories(parentDir);
        } catch (IOException e) {
            throw new ConfigLoadingException("Failed to create parent directories for config file", e);
        }

        if (!Files.exists(configFilePath)) {
            try (InputStream inputStream = plugin.getResource(sourceResource)) {
                if (inputStream == null) {
                    createEmptyConfig(configFilePath);
                    return new YamlConfiguration();
                }

                Files.copy(inputStream, configFilePath, StandardCopyOption.REPLACE_EXISTING);
                return YamlConfiguration.loadConfiguration(configFilePath.toFile());
            } catch (IOException e) {
                throw new ConfigLoadingException("Failed to copy config file from resource", e);
            }
        }

        try {
            return YamlConfiguration.loadConfiguration(configFilePath.toFile());
        } catch (Exception e) {
            throw new ConfigLoadingException("Failed to load existing config file", e);
        }
    }

    private void createEmptyConfig(Path configFilePath) throws IOException {
        try (var writer = Files.newBufferedWriter(configFilePath, StandardCharsets.UTF_8)) {
            writer.write("#  Empty configuration file generated automatically");
        }
        XLogger.warn("Generated empty configuration file: %s", configFilePath.getFileName());
    }

    public static class ConfigLoadingException extends RuntimeException {
        public ConfigLoadingException(String message, Throwable cause) {
            super(message, cause);
        }
    }

}
