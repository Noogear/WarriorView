package cn.warriorview.formatter;

import cn.warriorview.api.manager.NumberFormatManager;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 从 {@code plugins/WarriorView/number-format.yml} 加载数字量化缩写规则。
 *
 * <p>每个顶层键为规则名称，值为 阈值→后缀 的映射。
 * 规则名称通过 {@code indicator/} 配置文件中的 {@code number-format} 字段引用。</p>
 *
 * <h3>YAML 格式</h3>
 * <pre>
 * compact:
 *   1000:       "K"
 *   1000000:    "M"
 *   1000000000: "B"
 * </pre>
 */
public final class NumberFormatRegistry implements NumberFormatManager {

    private static final String RESOURCE_NAME = "number-format.yml";

    private final Plugin plugin;
    private final File   configFile;
    private final FileCache fileCache = new FileCache();

    private volatile Map<String, CompactNumberFormatter> store = Map.of();

    public NumberFormatRegistry(Plugin plugin) {
        this.plugin     = plugin;
        this.configFile = new File(plugin.getDataFolder(), RESOURCE_NAME);
    }

    // ── NumberFormatManager API ─────────────────────────────────────────────

    @Override
    public Collection<String> getRuleNames() {
        return store.keySet();
    }

    @Override
    public String formatNumber(String ruleName, double value, int precision) {
        CompactNumberFormatter fmt = store.get(ruleName);
        if (fmt == null) {
            int p = Math.max(0, precision);
            return p == 0 ? Long.toString((long) value) : String.format("%%.%df".formatted(p), value);
        }
        return fmt.format(value, Math.max(0, precision));
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

            Map<String, CompactNumberFormatter> map = new HashMap<>();
            for (var entry : root.entrySet()) {
                String name = String.valueOf(entry.getKey());
                if (!(entry.getValue() instanceof Map<?, ?> thresholdMap) || thresholdMap.isEmpty()) {
                    Log.warn(new Diagnostic(
                            LoadContext.location(name),
                            DiagnosticCategory.TYPE,
                            "Expected threshold map",
                            SourceView.yamlValueSnippet(List.of(name), entry.getValue(), Map.class)
                    ).format());
                    continue;
                }
                Map<Double, String> thresholds = new LinkedHashMap<>();
                for (var e : thresholdMap.entrySet()) {
                    try {
                        thresholds.put(Double.parseDouble(String.valueOf(e.getKey())),
                                       String.valueOf(e.getValue()));
                    } catch (NumberFormatException ex) {
                        Log.warn(new Diagnostic(
                                LoadContext.location(name, String.valueOf(e.getKey())),
                                DiagnosticCategory.TYPE,
                                "Threshold key is not a number",
                                SourceView.yamlValueSnippet(
                                        List.of(name, String.valueOf(e.getKey())),
                                        e.getKey(), Double.class)
                        ).format());
                    }
                }
                if (!thresholds.isEmpty()) map.put(name, CompactNumberFormatter.of(thresholds));
            }
            store = Collections.unmodifiableMap(map);
            Log.info("[NumberFormat] 从 number-format.yml 加载了 {} 套量化规则。", map.size());
        } catch (Exception e) {
            Log.error(new Diagnostic(new SourceLocation(RESOURCE_NAME, 0, 0),
                    DiagnosticCategory.CONFIG, "Failed to load: " + e.getMessage()).format(), e);
        } finally {
            LoadContext.clear();
        }
    }

    // ── 内部工厂（供 IndicatorConfigLoader 在加载期使用）──────────────────────

    /**
     * 根据量化规则名称和字符替换规则，在加载期构建一个组合的 {@link ValueFormatter}。
     *
     * <p>所有配置参数（{@code decimalPlaces}、{@code scale} 等）均在此时绑定到闭包中，
     * 运行期 {@link ValueFormatter#format(double)} 无额外分支判断。</p>
     *
     * @param nfName        量化规则名称（可为 null）
     * @param crr           字符替换规则注册表
     * @param crName        字符替换规则名称（可为 null）
     * @param decimalPlaces 小数位数（绑定到闭包，运行期不再传递）
     */
    public ValueFormatter buildFormatter(String nfName, CharReplaceRegistry crr, String crName, int decimalPlaces) {
        CompactNumberFormatter compact  = nfName != null ? store.get(nfName) : null;
        CharReplacer           replacer = crr.get(crName);

        final boolean hasFmt     = compact != null;
        final boolean hasReplace = !(replacer instanceof CharReplacer.None);

        if (!hasFmt && !hasReplace) return ValueFormatter.decimal(decimalPlaces);

        final int p = Math.max(0, decimalPlaces);

        if (hasFmt && !hasReplace) {
            CompactNumberFormatter f = compact;
            return v -> f.format(v, p);
        }

        if (!hasFmt) {
            CharReplacer r = replacer;
            ValueFormatter base = ValueFormatter.decimal(decimalPlaces);
            return v -> r.apply(base.format(v));
        }

        CompactNumberFormatter f = compact;
        CharReplacer r = replacer;
        return v -> r.apply(f.format(v, p));
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
