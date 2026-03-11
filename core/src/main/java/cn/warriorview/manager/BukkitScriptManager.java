package cn.warriorview.manager;

import cn.warriorview.api.manager.ScriptManager;
import cn.warriorview.action.BuiltinActions;
import cn.warriorview.util.Log;
import gloomlib.diagnostic.LoadContext;
import gloomlib.diagnostic.YamlLineIndex;
import gloomlib.diagnostic.Diagnostic;
import gloomlib.diagnostic.DiagnosticCategory;
import gloomlib.diagnostic.SourceLocation;
import gloomlib.script.api.ScriptHost;
import gloomlib.script.api.injection.ScriptInjector;
import gloomlib.script.core.handler.ActionNodeHandler;
import gloomlib.script.core.parser.ScriptParser;

import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Bukkit 平台脚本管理器。
 *
 * <p>实现 {@link ScriptHost}（gloomlib SPI）和 {@link ScriptManager}（公共 API）。
 * 从 {@code plugins/WarriorView/event-mapping/} 目录加载事件脚本，
 * 通过 gloomlib:script 解析并注册为 Bukkit 事件监听器。</p>
 */
public class BukkitScriptManager implements ScriptHost, ScriptManager {

    private static final String EVENT_MAPPING_DIR = "event-mapping";
    private static final String[] DEFAULT_RESOURCES = {
            "event-mapping/damage-indicator.yml",
            "event-mapping/regain-indicator.yml"
    };

    private final Plugin plugin;
    private final File scriptFolder;
    private final ScriptInjector injector;
    /** 已加载的 mapping ID 集合（按文件名/id 字段）。 */
    private final Set<String> loadedIds = new LinkedHashSet<>();

    public BukkitScriptManager(Plugin plugin) {
        this.plugin = plugin;
        this.scriptFolder = new File(plugin.getDataFolder(), EVENT_MAPPING_DIR);

        // 绑定内置动作
        ActionNodeHandler.registry().scanAndRegister(BuiltinActions.class);

        // 实例化注入器
        this.injector = new ScriptInjector(this);
    }

    /**
     * 重载并注入所有事件脚本。
     * 加载时捕获异常以做到单文件错误隔离。
     */
    public void reloadScripts() {
        unloadScripts();
        loadedIds.clear();

        // 首次运行时从 JAR 复制默认脚本
        if (!scriptFolder.exists() || !containsYaml(scriptFolder)) {
            scriptFolder.mkdirs();
            copyDefaultResources();
        }

        File[] files = scriptFolder.listFiles((dir, name) -> name.endsWith(".yml") || name.endsWith(".yaml"));
        if (files == null || files.length == 0) {
            Log.info("No event-mapping scripts found.");
            return;
        }

        int success = 0;
        for (File file : files) {
            String content;
            try {
                content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                Log.error(new Diagnostic(new SourceLocation(file.getName(), 0, 0),
                        DiagnosticCategory.CONFIG, "Failed to read: " + e.getMessage()).format(), e);
                continue;
            }

            LoadContext.set(file.getName(), YamlLineIndex.buildFromString(content));
            try {
                Map<String, Object> rootMap = ScriptParser.parseYaml(content);
                // 提取 id：优先用 YAML 中的 id 字段，否则用文件名
                String scriptId = rootMap.containsKey("id")
                        ? String.valueOf(rootMap.get("id"))
                        : file.getName();
                rootMap.put("id", scriptId);

                injector.inject(rootMap);
                loadedIds.add(scriptId);
                success++;
            } catch (Exception e) {
                Log.error(new Diagnostic(new SourceLocation(file.getName(), 0, 0),
                        DiagnosticCategory.CONFIG, "Failed to load: " + e.getMessage()).format(), e);
            } finally {
                LoadContext.clear();
            }
        }
        Log.info("[EventMapping] Loaded {} of {} scripts.", success, files.length);
    }

    /**
     * 卸载所有活跃的脚本事件。
     */
    public void unloadScripts() {
        injector.ejectAll();
    }

    // ── ScriptManager API 实现 ──────────────────────────────────────────────────

    @Override
    public void registerActionClass(Class<?> clazz) {
        ActionNodeHandler.registry().scanAndRegister(clazz);
        Log.info("[ScriptManager] Registered action class: {}", clazz.getSimpleName());
    }

    @Override
    public void reloadMappings() {
        reloadScripts();
    }

    @Override
    public void unloadMappings() {
        unloadScripts();
        loadedIds.clear();
    }

    @Override
    public Collection<String> getMappingIds() {
        return Collections.unmodifiableSet(loadedIds);
    }

    // ── ScriptHost SPI ──────────────────────────────────────────────────

    @Override
    public Object registerEvent(Class<?> payloadClass, int priority, Consumer<Object> handler) {
        return registerEvent(payloadClass, priority, false, handler);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object registerEvent(Class<?> payloadClass, int priority, boolean ignoreCancelled,
                                Consumer<Object> handler) {
        if (!Event.class.isAssignableFrom(payloadClass)) {
            throw new IllegalArgumentException(
                    "Payload class must extend org.bukkit.event.Event: " + payloadClass.getName());
        }

        Class<? extends Event> eventClass = (Class<? extends Event>) payloadClass;
        EventPriority bukkitPriority = switch (priority) {
            case -2 -> EventPriority.LOWEST;
            case -1 -> EventPriority.LOW;
            case  1 -> EventPriority.HIGH;
            case  2 -> EventPriority.HIGHEST;
            case  3 -> EventPriority.MONITOR;
            default -> EventPriority.NORMAL;
        };

        Listener listener = new Listener() {};
        EventExecutor executor = (l, event) -> {
            if (eventClass.isInstance(event)) {
                handler.accept(event);
            }
        };

        Bukkit.getPluginManager().registerEvent(eventClass, listener, bukkitPriority, executor,
                plugin, ignoreCancelled);
        return listener;
    }

    @Override
    public void unregisterEvent(Object registrationToken) {
        if (registrationToken instanceof Listener listener) {
            HandlerList.unregisterAll(listener);
        }
    }

    // ── 文件工具 ────────────────────────────────────────────────────────

    private static boolean containsYaml(File dir) {
        File[] files = dir.listFiles(f -> f.getName().endsWith(".yml") || f.getName().endsWith(".yaml"));
        return files != null && files.length > 0;
    }

    private void copyDefaultResources() {
        for (String resource : DEFAULT_RESOURCES) {
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
                        "Could not copy default: " + e.getMessage()).format());
            }
        }
    }
}
