package cn.warriorView.manager;

import cn.warriorView.Main;
import cn.warriorView.configuration.file.Config;
import cn.warriorView.configuration.file.Language;
import cn.warriorView.configuration.form.ConfigurationManager;
import cn.warriorView.object.Offset;
import cn.warriorView.object.scale.ScaleFactory;
import cn.warriorView.util.MathUtil;
import cn.warriorView.util.RegistryUtil;
import cn.warriorView.util.XLogger;
import cn.warriorView.view.ViewParams;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Set;

public class ConfigManager {
    private final File configFile;
    private final File languageFile;
    private final AnimationManager animationManager;
    private final FormatManager formatManager;
    private final Main plugin;
    private final ViewManager viewManager;

    public ConfigManager(Main main) {
        this.plugin = main;
        animationManager = new AnimationManager();
        formatManager = new FormatManager();
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
        init();
        if (!Config.enabled) {
            XLogger.info("WarriorView is not enabled!");
            return;
        }
        YamlConfiguration replaceConfig = new YamlConfiguration();
        replaceConfig.options().pathSeparator(':');
        formatManager.load(loadOrCreateConfig(Config.replacement, "replacement.yml", replaceConfig));
        animationManager.load(loadOrCreateConfig(Config.animation, "animation.yml", null));
        loadDamageView(loadOrCreateConfig(Config.damageEntity.apply, "damage_cause.yml", null));
        loadRegainHealth(loadOrCreateConfig(Config.regainHealth.apply, "regain_reason.yml", null));
    }

    public void init() {
        viewManager.init();
        animationManager.init();
        formatManager.init();
    }

    private void loadDamageView(YamlConfiguration viewFile) {
        try {
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
        try {
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
                defaults.offsetUp,
                defaults.offsetApproach,
                defaults.teleportDuration
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
                defaults.offsetUp,
                defaults.offsetApproach,
                defaults.teleportDuration
        );
    }

    private ViewParams getViewParams(ConfigurationSection section, String textFormat, String replacement, String scale, boolean shadow, double opacity, float viewRange, byte viewMarge, int backgroundColor, boolean seeThrough, boolean onlyPlayer, List<String> animation, String position, double offsetUp, double offsetApproach, int teleportDuration) {
        if (section == null) {
            if ("DAMAGE".equalsIgnoreCase(position)) {
                XLogger.err("The default config cannot use \"damage\" as the position.");
                position = "EYE";
            }
            return new ViewParams(
                    formatManager.getTextFormat(textFormat, replacement),
                    ScaleFactory.create(scale),
                    shadow,
                    MathUtil.opacityFromPercent(opacity),
                    viewRange,
                    viewMarge,
                    backgroundColor,
                    seeThrough,
                    onlyPlayer,
                    animationManager.getAnimation(animation),
                    position,
                    new Offset(offsetUp, offsetApproach),
                    teleportDuration
            );
        }
        return new ViewParams(
                formatManager.getTextFormat(section.getString("text-format", textFormat), section.getString("replacement", replacement)),
                ScaleFactory.create(section.getString("scale", scale)),
                section.getBoolean("shadow", shadow),
                MathUtil.opacityFromPercent(section.getDouble("opacity", opacity)),
                MathUtil.round(section.getDouble("view-range", viewRange), 1),
                MathUtil.convertIntToByte(section.getInt("view-marge", viewMarge)),
                section.getInt("background-color", backgroundColor),
                section.getBoolean("see-through", seeThrough),
                section.getBoolean("only-player", onlyPlayer),
                animationManager.getAnimation(section.getStringList("animation"), animation),
                section.getString("position", position),
                new Offset(section.getDouble("offset-up", offsetUp), section.getDouble("offset-approach", offsetApproach)),
                section.getInt("teleport-duration", teleportDuration)
        );
    }


    public YamlConfiguration loadOrCreateConfig(String targetFileName, String sourceResource, @Nullable YamlConfiguration config) throws ConfigLoadingException {
        Path configFilePath = plugin.getDataFolder().toPath().resolve(targetFileName);
        Path parentDir = configFilePath.getParent();

        try {
            Files.createDirectories(parentDir);
        } catch (IOException e) {
            XLogger.err("Failed to create parent directories for config file" + e);
        }

        if (!Files.exists(configFilePath)) {
            try (InputStream inputStream = plugin.getResource(sourceResource)) {
                if (inputStream == null) {
                    createEmptyConfig(configFilePath);
                    return new YamlConfiguration();
                }

                Files.copy(inputStream, configFilePath, StandardCopyOption.REPLACE_EXISTING);
                if (config == null) {
                    return YamlConfiguration.loadConfiguration(configFilePath.toFile());
                } else {
                    try {
                        config.load(configFilePath.toFile());
                    } catch (Exception e) {
                        XLogger.err("Failed to load config file" + e);
                    }
                    return config;
                }

            } catch (IOException e) {
                XLogger.err("Failed to copy config file from resource" + e);
            }
        }
        if (config == null) {
            return YamlConfiguration.loadConfiguration(configFilePath.toFile());
        } else {
            try {
                config.load(configFilePath.toFile());
            } catch (Exception e) {
                XLogger.err("Failed to load existing config file" + e);
            }
            return config;
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
