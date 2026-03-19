package cn.warriorview.animation.load;

import cn.warriorview.animation.definition.AnimationDef;
import cn.warriorview.animation.definition.PresetDef;
import cn.warriorview.animation.parse.EquationParser;
import cn.warriorview.animation.parse.KeyframeParser;
import cn.warriorview.animation.parse.SettingsParser;
import cn.warriorview.animation.registry.AnimationRegistry;
import cn.warriorview.util.Log;
import gloomlib.configuration.api.ConfigurationManager;
import gloomlib.configuration.api.DirectoryConfiguration;
import gloomlib.diagnostic.Diagnostic;
import gloomlib.diagnostic.DiagnosticCategory;
import gloomlib.diagnostic.LoadContext;
import gloomlib.diagnostic.SourceLocation;
import gloomlib.diagnostic.SourceView;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.List;

/**
 * Loads animation and preset definitions from YAML files stored in
 * {@code plugins/WarriorView/animations/} (and its sub-directories).
 *
 * <h3>File type detection</h3>
 * The root key of each YAML file determines how it is parsed:
 * <ul>
 *   <li>{@code preset:} – the file is a <em>preset library</em> and is processed first.</li>
 *   <li>{@code animation:} – the file is an <em>animation registry</em> and is processed
 *       after all preset libraries have been loaded.</li>
 * </ul>
 *
 * <h3>Loading order</h3>
 * <ol>
 *   <li>Copy bundled defaults if the animations directory is empty.</li>
 *   <li>Pass 1 – load all {@code preset:} sections; register into registry.</li>
 *   <li>Pass 2 – load all {@code animation:} sections (may reference presets); register.</li>
 * </ol>
 */
public final class AnimationFileLoader {

    private static final String ANIMATIONS_DIR = "animations";

    private final JavaPlugin plugin;
    private DirectoryConfiguration<AnimationDef> presetConfig;
    private DirectoryConfiguration<AnimationDef> animConfig;

    public AnimationFileLoader(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Returns {@code true} if any animation file has changed since the last load.
     */
    public boolean hasChanges() {
        if (presetConfig == null || animConfig == null) return true;
        return !presetConfig.isFresh() || !animConfig.isFresh();
    }

    /**
     * Loads all animation and preset definitions into {@code registry}.
     * The registry is cleared before loading starts.
     *
     * @param registry the target registry (will be cleared first)
     */
    public void load(AnimationRegistry registry) {
        registry.clear();

        File animDir = new File(plugin.getDataFolder(), ANIMATIONS_DIR);
        ensureDefaults(animDir);

        try {
            // Pass 1: presets — must be registered before animations reference them
            presetConfig = ConfigurationManager
                    .directory(animDir, (name, sec) -> parsePreset(name, sec))
                    .rootKey("preset")
                    .recursive()
                    .load();
            presetConfig.all().values().forEach(registry::register);

            // Pass 2: animations (factory can look up presets via registry)
            animConfig = ConfigurationManager
                    .directory(animDir, (name, sec) -> parseAnimation(name, sec, registry))
                    .rootKey("animation")
                    .recursive()
                    .load();
            animConfig.all().values().forEach(registry::register);

            Log.info("[AnimationFileLoader] Loaded {} animation(s)/preset(s).",
                    presetConfig.all().size() + animConfig.all().size());

        } catch (Exception e) {
            Log.error("[AnimationFileLoader] Failed to load: {}", e.getMessage());
        }
    }

    // ── per-entry parsers ─────────────────────────────────────────────────────

    @Nullable
    private static AnimationDef parsePreset(String name, ConfigurationSection sec) {
        String type = sec.getString("type", "keyframe").toLowerCase();
        return switch (type) {
            case "keyframe" -> KeyframeParser.parse(name, sec);
            case "equation" -> EquationParser.parse(name, sec);
            default -> {
                Log.warn(new Diagnostic(
                        LoadContext.location("preset", name, "type"),
                        DiagnosticCategory.PARSE,
                        "Unknown animation type '" + type + "'",
                        SourceView.yamlValueSnippet(List.of("preset", name, "type"), type, null)
                ).format());
                yield null;
            }
        };
    }

    @Nullable
    private static AnimationDef parseAnimation(String name, ConfigurationSection sec,
                                               AnimationRegistry registry) {
        String type = sec.getString("type", "keyframe").toLowerCase();
        return switch (type) {
            case "keyframe" -> KeyframeParser.parse(name, sec);
            case "equation" -> EquationParser.parse(name, sec);
            case "preset"   -> resolvePresetRef(name, sec, registry);
            default -> {
                Log.warn(new Diagnostic(
                        LoadContext.location("animation", name, "type"),
                        DiagnosticCategory.PARSE,
                        "Unknown animation type '" + type + "'",
                        SourceView.yamlValueSnippet(List.of("animation", name, "type"), type, null)
                ).format());
                yield null;
            }
        };
    }

    @Nullable
    private static AnimationDef resolvePresetRef(String name, ConfigurationSection entry,
                                                  AnimationRegistry registry) {
        String refId = entry.getString("id");
        if (refId == null) {
            Log.warn(new Diagnostic(
                    LoadContext.location("animation", name, "id"),
                    DiagnosticCategory.SEMANTIC,
                    "Missing required field: id"
            ).format());
            return null;
        }
        AnimationDef resolved = registry.get(refId);
        if (resolved == null) {
            Log.warn(new Diagnostic(
                    LoadContext.location("animation", name, "id"),
                    DiagnosticCategory.SEMANTIC,
                    "References unknown animation '" + refId + "'",
                    SourceView.yamlValueSnippet(List.of("animation", name, "id"), refId, null)
            ).format());
            return null;
        }
        var settings = SettingsParser.parse(entry.getConfigurationSection("settings"));
        return new PresetDef(name, settings, resolved);
    }

    // ── default resources ─────────────────────────────────────────────────────

    private void ensureDefaults(File animDir) {
        if (animDir.exists() && containsYaml(animDir)) return;
        animDir.mkdirs();
        copyDefaultResources(animDir);
    }

    private static boolean containsYaml(File dir) {
        File[] files = dir.listFiles(f -> f.getName().endsWith(".yml"));
        return files != null && files.length > 0;
    }

    private void copyDefaultResources(File targetDir) {
        String[] defaults = {
                "animations/default.yml",
                "animations/examples.yml",
                "animations/presets/basic.yml",
                "animations/presets/common.yml",
                "animations/presets/dynamic.yml",
                "animations/presets/equation.yml"
        };
        for (String resource : defaults) {
            File dest = new File(plugin.getDataFolder(), resource);
            if (dest.exists()) continue;
            try {
                dest.getParentFile().mkdirs();
                try (InputStream in = plugin.getResource(resource)) {
                    if (in != null) Files.copy(in, dest.toPath());
                }
            } catch (IOException e) {
                Log.warn(new Diagnostic(new SourceLocation(resource, 0, 0),
                        DiagnosticCategory.CONFIG,
                        "Could not copy default resource: " + e.getMessage()).format());
            }
        }
    }
}
