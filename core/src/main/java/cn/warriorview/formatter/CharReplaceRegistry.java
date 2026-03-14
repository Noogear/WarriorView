package cn.warriorview.formatter;

import cn.warriorview.api.manager.CharReplaceManager;
import cn.warriorview.util.Log;

import gloomlib.diagnostic.LoadContext;
import gloomlib.configuration.api.util.FileCache;
import gloomlib.diagnostic.YamlLineIndex;
import gloomlib.diagnostic.Diagnostic;
import gloomlib.diagnostic.DiagnosticCategory;
import gloomlib.diagnostic.SourceLocation;
import gloomlib.diagnostic.SourceView;
import org.bukkit.plugin.Plugin;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 从 {@code plugins/WarriorView/char-replace.yml} 加载字符替换规则。
 *
 * <p>每个顶层键为规则名称，值为 单字符→替换串 的映射。
 * 规则名称通过 {@code indicator/} 配置文件中的 {@code char-replace} 字段引用。</p>
 *
 * <h3>YAML 格式</h3>
 * <pre>
 * double-struck:
 *   "0": "𝟘"
 *   "1": "𝟙"
 *   ".": "·"
 * </pre>
 */
public final class CharReplaceRegistry implements CharReplaceManager {

    private static final String RESOURCE_NAME = "char-replace.yml";

    private final Plugin plugin;
    private final File   configFile;
    private final FileCache fileCache = new FileCache();

    private volatile Map<String, CharReplacer> store = Map.of();

    public CharReplaceRegistry(Plugin plugin) {
        this.plugin     = plugin;
        this.configFile = new File(plugin.getDataFolder(), RESOURCE_NAME);
    }

    // ── CharReplaceManager API ────────────────────────────────────────────────

    @Override
    public Collection<String> getRuleNames() {
        return store.keySet();
    }

    @Override
    public String replaceChars(String ruleName, String input) {
        return get(ruleName).apply(input);
    }

    @Override
    public void reload() {
        if (!configFile.exists()) copyDefault();

        String content;
        try {
            content = fileCache.read(configFile);
        } catch (IOException e) {
            Log.error(new Diagnostic(new SourceLocation(RESOURCE_NAME, 0, 0),
                    DiagnosticCategory.CONFIG, "Failed to read: " + e.getMessage()).format(), e);
            return;
        }

        parseAndStore(content);
    }

    /**
     * 仅在文件发生变更时重新加载。
     *
     * @return {@code true} 如果文件已变更并重新加载
     */
    public boolean smartReload() {
        if (!configFile.exists()) { copyDefault(); reload(); return true; }
        if (fileCache.isFresh(configFile)) return false;

        String content;
        try {
            content = fileCache.readIfChanged(configFile);
        } catch (IOException e) {
            Log.error(new Diagnostic(new SourceLocation(RESOURCE_NAME, 0, 0),
                    DiagnosticCategory.CONFIG, "Failed to read: " + e.getMessage()).format(), e);
            return false;
        }
        if (content == null) return false;

        parseAndStore(content);
        return true;
    }

    private void parseAndStore(String content) {
        LoadContext.set(configFile.getName(), YamlLineIndex.buildFromString(content));
        try {
            Map<?, ?> root = new Yaml().load(content);
            if (root == null) { store = Map.of(); return; }

            Map<String, CharReplacer> map = new HashMap<>();
            for (var entry : root.entrySet()) {
                String name = String.valueOf(entry.getKey());
                if (!(entry.getValue() instanceof Map<?, ?> rMap) || rMap.isEmpty()) {
                    Log.warn(new Diagnostic(
                            LoadContext.location(name),
                            DiagnosticCategory.TYPE,
                            "Expected character replacement map",
                            SourceView.yamlValueSnippet(List.of(name), entry.getValue(), Map.class)
                    ).format());
                    continue;
                }
                String[] latin = new String[256];
                Map<Integer, String> ext = null;
                boolean hasAny = false;

                for (var e : rMap.entrySet()) {
                    String keyStr = String.valueOf(e.getKey());
                    if (keyStr.isEmpty()) continue;
                    String value = String.valueOf(e.getValue());
                    int cp       = keyStr.codePointAt(0);
                    if (cp < 256) {
                        latin[cp] = value;
                        hasAny = true;
                    } else {
                        if (ext == null) ext = new HashMap<>();
                        ext.put(cp, value);
                        hasAny = true;
                    }
                }
                if (hasAny) map.put(name, CharReplacer.of(latin, ext));
            }
            store = Collections.unmodifiableMap(map);
            Log.info("[CharReplace] 从 char-replace.yml 加载了 {} 套替换规则。", map.size());
        } catch (Exception e) {
            Log.error(new Diagnostic(new SourceLocation(RESOURCE_NAME, 0, 0),
                    DiagnosticCategory.CONFIG, "Failed to load: " + e.getMessage()).format(), e);
        } finally {
            LoadContext.clear();
        }
    }

    // ── 内部查询（供 NumberFormatRegistry.buildFormatter 使用）──────────────

    /** 按名称查找替换规则；找不到则返回 {@link CharReplacer#NONE}。 */
    CharReplacer get(String name) {
        if (name == null || name.isEmpty()) return CharReplacer.NONE;
        CharReplacer result = store.get(name);
        if (result == null) {
            cn.warriorview.util.Log.warn("[CharReplace] Rule '{}' not found in registry. Check char-replace.yml.", name);
            return CharReplacer.NONE;
        }
        return result;
    }

    // ── 文件工具 ────────────────────────────────────────────────────────────

    private void copyDefault() {
        try {
            configFile.getParentFile().mkdirs();
            try (InputStream in = plugin.getResource(RESOURCE_NAME)) {
                if (in != null) Files.copy(in, configFile.toPath());
            }
        } catch (IOException e) {
            Log.warn(new Diagnostic(new SourceLocation(RESOURCE_NAME, 0, 0),
                    DiagnosticCategory.CONFIG,
                    "Could not copy default: " + e.getMessage()).format());
        }
    }
}
