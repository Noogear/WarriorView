package cn.warriorview.animation.load;

import cn.warriorview.animation.definition.AnimationDef;
import cn.warriorview.animation.definition.PresetDef;
import cn.warriorview.util.Log;
import cn.warriorview.animation.parse.EquationParser;
import cn.warriorview.animation.parse.KeyframeParser;
import cn.warriorview.animation.parse.SettingsParser;
import cn.warriorview.animation.registry.AnimationRegistry;
import gloomlib.diagnostic.LoadContext;
import gloomlib.configuration.api.util.FileCache;
import gloomlib.diagnostic.YamlLineIndex;
import gloomlib.diagnostic.Diagnostic;
import gloomlib.diagnostic.DiagnosticCategory;
import gloomlib.diagnostic.SourceLocation;
import gloomlib.diagnostic.SourceView;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
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
 *   <li>Recursively collect all {@code *.yml} files from the animations directory.</li>
 *   <li>Pass 1 – load all files whose root key is {@code preset:} into the registry.</li>
 *   <li>Pass 2 – load all files whose root key is {@code animation:} into the registry.</li>
 * </ol>
 *
 * <h3>Default files</h3>
 * If the animations directory does not exist or contains no {@code .yml} files, the
 * loader copies the bundled default files from the plugin JAR's
 * {@code animations/} resource folder.
 */
public final class AnimationFileLoader {

    private static final String ANIMATIONS_DIR = "animations";

    private final JavaPlugin plugin;
    private final FileCache fileCache = new FileCache();

    public AnimationFileLoader(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Returns {@code true} if any animation file has changed since the last load.
     */
    public boolean hasChanges() {
        File animDir = new File(plugin.getDataFolder(), ANIMATIONS_DIR);
        if (!animDir.exists()) return true;
        List<File> all = new ArrayList<>();
        collectAllYml(animDir, all);
        if (all.isEmpty()) return fileCache.size() > 0;
        // Check if any file changed or new files appeared
        for (File f : all) {
            if (!fileCache.isFresh(f)) return true;
        }
        // Check if any cached file got deleted (count mismatch)
        return fileCache.size() != all.size();
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

        // --- Copy defaults from JAR if needed ---
        if (!animDir.exists() || !containsYaml(animDir)) {
            animDir.mkdirs();
            copyDefaultResources(animDir);
        }

        // --- Collect all yml files ---
        List<File> presetFiles    = new ArrayList<>();
        List<File> animationFiles = new ArrayList<>();
        collectFiles(animDir, presetFiles, animationFiles);

        int count = 0;

        // --- Pass 1: presets ---
        for (File f : presetFiles) {
            LoadContext.set(f.getName(), YamlLineIndex.build(f));
            try {
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(f);
                ConfigurationSection presetSection = yaml.getConfigurationSection("preset");
                if (presetSection == null) continue;
                count += loadSection(presetSection, registry, true);
            } finally {
                LoadContext.clear();
            }
        }

        // --- Pass 2: animations (may reference presets loaded above) ---
        for (File f : animationFiles) {
            LoadContext.set(f.getName(), YamlLineIndex.build(f));
            try {
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(f);
                ConfigurationSection animSection = yaml.getConfigurationSection("animation");
                if (animSection == null) continue;
                count += loadSection(animSection, registry, false);
            } finally {
                LoadContext.clear();
            }
        }

        Log.info("[AnimationFileLoader] Loaded {} animation(s)/preset(s).", count);
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    /**
     * Loads all entries found in a YAML section (each key = one definition name).
     *
     * @param section      the {@code animation:} or {@code preset:} section
     * @param registry     the target registry
     * @param isPresetFile {@code true} if this file came from the preset pass
     * @return number of definitions successfully loaded
     */
    private int loadSection(ConfigurationSection section, AnimationRegistry registry, boolean isPresetFile) {
        String root = isPresetFile ? "preset" : "animation";
        int count = 0;
        for (String name : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(name);
            if (entry == null) continue;

            String type = entry.getString("type", "keyframe").toLowerCase();
            AnimationDef def = switch (type) {
                case "keyframe" -> KeyframeParser.parse(name, entry);
                case "equation" -> EquationParser.parse(name, entry);
                case "preset"   -> resolvePresetRef(root, name, entry, registry);
                default -> {
                    Log.warn(new Diagnostic(
                            LoadContext.location(root, name, "type"),
                            DiagnosticCategory.PARSE,
                            "Unknown animation type '" + type + "'",
                            SourceView.yamlValueSnippet(List.of(root, name, "type"), type, null)
                    ).format());
                    yield null;
                }
            };

            if (def != null) {
                registry.register(def);
                count++;
            }
        }
        return count;
    }

    /**
     * Builds a {@link PresetDef} by resolving the referenced preset/animation from
     * the registry.  The referenced entry must already be loaded (i.e., presets
     * are always loaded before animations).
     */
    private AnimationDef resolvePresetRef(String root, String name, ConfigurationSection entry, AnimationRegistry registry) {
        String refId = entry.getString("id");
        if (refId == null) {
            Log.warn(new Diagnostic(
                    LoadContext.location(root, name, "id"),
                    DiagnosticCategory.SEMANTIC,
                    "Missing required field: id"
            ).format());
            return null;
        }
        AnimationDef resolved = registry.get(refId);
        if (resolved == null) {
            Log.warn(new Diagnostic(
                    LoadContext.location(root, name, "id"),
                    DiagnosticCategory.SEMANTIC,
                    "References unknown animation '" + refId + "'",
                    SourceView.yamlValueSnippet(List.of(root, name, "id"), refId, null)
            ).format());
            return null;
        }
        var settings = SettingsParser.parse(entry.getConfigurationSection("settings"));
        return new PresetDef(name, settings, resolved);
    }

    // -------------------------------------------------------------------------
    // File utilities
    // -------------------------------------------------------------------------

    /** Recursively walks {@code dir} and buckets .yml files into preset vs animation lists. */
    private static void collectFiles(File dir, List<File> presets, List<File> animations) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File f : children) {
            if (f.isDirectory()) {
                collectFiles(f, presets, animations);
            } else if (f.getName().endsWith(".yml")) {
                // Peek at the root key to determine the bucket
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(f);
                if (yaml.contains("preset")) {
                    presets.add(f);
                } else if (yaml.contains("animation")) {
                    animations.add(f);
                }
            }
        }
    }

    /** Recursively collects all .yml files for change detection. */
    private static void collectAllYml(File dir, List<File> result) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File f : children) {
            if (f.isDirectory()) collectAllYml(f, result);
            else if (f.getName().endsWith(".yml")) result.add(f);
        }
    }

    private static boolean containsYaml(File dir) {
        File[] files = dir.listFiles(f -> f.getName().endsWith(".yml"));
        return files != null && files.length > 0;
    }

    /** Copies bundled default YAML resources from the JAR into {@code targetDir}. */
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
                    if (in != null) {
                        Files.copy(in, dest.toPath());
                    }
                }
            } catch (IOException e) {
                Log.warn(new Diagnostic(new SourceLocation(resource, 0, 0),
                        DiagnosticCategory.CONFIG,
                        "Could not copy default resource: " + e.getMessage()).format());
            }
        }
    }
}
