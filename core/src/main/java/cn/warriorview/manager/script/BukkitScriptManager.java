package cn.warriorview.manager.script;

import cn.warriorview.script.api.ScriptHost;
import cn.warriorview.script.handler.ActionNodeHandler;
import cn.warriorview.script.bukkit.BuiltinActions;
import cn.warriorview.script.core.ScriptCompileException;
import cn.warriorview.script.injection.ScriptInjector;

import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Bukkit 平台脚本管理器。
 * <p>
 * 实现了 {@link ScriptHost}，接管脚本的 Listener 生命周期，
 * 负责脚本加载、热重载以及编译异常的文件级隔离。
 */
public class BukkitScriptManager implements ScriptHost {

    private final Plugin plugin;
    private final File scriptFolder;
    private final ScriptInjector injector;
    private final List<Object> activeTokens = new ArrayList<>();

    public BukkitScriptManager(Plugin plugin) {
        this.plugin = plugin;
        this.scriptFolder = new File(plugin.getDataFolder(), "scripts");
        if (!this.scriptFolder.exists()) {
            this.scriptFolder.mkdirs();
        }

        // 绑定内置动作
        ActionNodeHandler.registry().scanAndRegister(BuiltinActions.class);

        // 实例化解耦后的注入器并传入自身作为 Host
        this.injector = new ScriptInjector(this);
    }

    /**
     * 重载并注入所有脚本。
     * 加载时捕获异常以做到单文件错误隔离。
     */
    public void reloadScripts() {
        unloadScripts(); // 清理旧有 Listener

        File[] files = scriptFolder.listFiles((dir, name) -> name.endsWith(".yml") || name.endsWith(".yaml"));
        if (files == null || files.length == 0) {
            plugin.getLogger().info("No scripts found in 'scripts' folder.");
            return;
        }

        int success = 0;
        for (File file : files) {
            try {
                YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
                java.util.Map<String, Object> rootMap = config.getValues(false);

                // 嵌套层级的特判解包，因为 getValues(false) 对深层可能保留为 ConfigurationSection
                if (config.isList("flow")) {
                    rootMap.put("flow", config.getMapList("flow"));
                }
                if (config.isConfigurationSection("variables")) {
                    rootMap.put("variables", config.getConfigurationSection("variables").getValues(false));
                }

                injector.inject(rootMap);
                success++;
            } catch (ScriptCompileException e) {
                plugin.getLogger().log(Level.SEVERE,
                        "Failed to compile script: " + file.getName() + " -> " + e.getMessage());
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Unexpected error loading script: " + file.getName(), e);
            }
        }
        plugin.getLogger()
                .info("Loaded " + success + " scripts successfully (" + (files.length - success) + " failed).");
    }

    /**
     * 卸载所有活跃的脚本事件。
     */
    public void unloadScripts() {
        // 利用抽象层清理
        injector.ejectAll();
        activeTokens.clear();
    }

    // ======================== ScriptHost SPI 实现 ========================

    @Override
    @SuppressWarnings("unchecked")
    public Object registerEvent(Class<?> payloadClass, int priority, Consumer<Object> handler) {
        if (!Event.class.isAssignableFrom(payloadClass)) {
            throw new IllegalArgumentException(
                    "Payload class must extend org.bukkit.event.Event: " + payloadClass.getName());
        }

        Class<? extends Event> eventClass = (Class<? extends Event>) payloadClass;

        // 解析优先级：简单的边界映射
        EventPriority bukkitPriority;
        switch (priority) {
            case -2 -> bukkitPriority = EventPriority.LOWEST;
            case -1 -> bukkitPriority = EventPriority.LOW;
            case 1 -> bukkitPriority = EventPriority.HIGH;
            case 2 -> bukkitPriority = EventPriority.HIGHEST;
            case 3 -> bukkitPriority = EventPriority.MONITOR;
            default -> bukkitPriority = EventPriority.NORMAL; // 0 == NORMAL
        }

        Listener listener = new Listener() {
        }; // 匿名占位实例

        EventExecutor executor = (l, event) -> {
            if (eventClass.isInstance(event)) {
                handler.accept(event);
            }
        };

        Bukkit.getPluginManager().registerEvent(eventClass, listener, bukkitPriority, executor, plugin);

        return listener;
    }

    @Override
    public void unregisterEvent(Object registrationToken) {
        if (registrationToken instanceof Listener listener) {
            HandlerList.unregisterAll(listener);
        }
    }
}
