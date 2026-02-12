package cn.warriorview.listener;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;

import java.util.*;
import java.util.logging.Logger;

/**
 * 监听器配置解析器。
 * 从 {@link org.bukkit.configuration.ConfigurationSection} 或
 * {@link java.util.Map}
 * 解析出 {@link cn.warriorview.listener.ListenerConfigParser.ListenerConfig}，
 * 供 {@link cn.warriorview.listener.DynamicListenerRuntime} 消费。
 *
 * <pre>{@code
 * # YAML 格式
 * event: org.bukkit.event.entity.EntityDeathEvent
 * priority: NORMAL
 * ignore-cancelled: true
 * variables:
 *   killer: "entity.killer"
 * flow:
 *   - type: check
 *     variable: killer
 *     op: not_null
 * }</pre>
 */
public final class ListenerConfigParser {

    private ListenerConfigParser() {
    }

    /**
     * 从 {@link org.bukkit.configuration.ConfigurationSection} 解析完整配置。
     *
     * @param section    YAML 配置节
     * @param listenerId 监听器标识（仅用于日志）
     * @param logger     日志记录器
     * @return 解析结果，解析失败返回 null
     */
    public static ListenerConfig parse(ConfigurationSection section, String listenerId, Logger logger) {
        // variables → Map<String, String>
        Map<String, String> variables = new LinkedHashMap<>();
        ConfigurationSection varSection = section.getConfigurationSection("variables");
        if (varSection != null) {
            for (String key : varSection.getKeys(false)) {
                variables.put(key, varSection.getString(key));
            }
        }

        // flow → List<Map<String, Object>>
        List<Map<String, Object>> flow = new ArrayList<>();
        for (Map<?, ?> rawNode : section.getMapList("flow")) {
            Map<String, Object> node = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : rawNode.entrySet()) {
                node.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            flow.add(node);
        }

        Map<String, Object> compilerConfig = Map.of("variables", variables, "flow", flow);

        return buildConfig(
                section.getString("event"),
                section.getString("priority"),
                section.getBoolean("ignore-cancelled", false),
                compilerConfig,
                listenerId, logger);
    }

    /**
     * 从纯 {@link java.util.Map} 解析（兼容非 Bukkit 配置源）。
     *
     * @param config     原始 Map 配置
     * @param listenerId 监听器标识
     * @param logger     日志记录器
     * @return 解析结果，解析失败返回 null
     */
    public static ListenerConfig parseFromMap(Map<String, Object> config, String listenerId, Logger logger) {
        return buildConfig(
                (String) config.get("event"),
                config.containsKey("priority") ? String.valueOf(config.get("priority")) : null,
                Boolean.parseBoolean(String.valueOf(config.getOrDefault("ignore-cancelled", "false"))),
                config,
                listenerId, logger);
    }

    // ───────── 公共构建管道（消除双入口的重复逻辑） ─────────

    private static ListenerConfig buildConfig(String eventName, String priorityStr,
            boolean ignoreCancelled, Map<String, Object> compilerConfig,
            String listenerId, Logger logger) {
        if (eventName == null || eventName.isBlank()) {
            logger.severe("[ListenerConfig] '" + listenerId + "' 缺少 'event' 字段");
            return null;
        }

        Class<? extends Event> eventClass = resolveEventClass(eventName, logger);
        if (eventClass == null) {
            logger.severe("[ListenerConfig] '" + listenerId + "' 无法解析事件类 '" + eventName + "'");
            return null;
        }

        EventPriority priority = resolvePriority(priorityStr, logger);
        return new ListenerConfig(eventClass, priority, ignoreCancelled, compilerConfig);
    }

    // ───────── 内部解析 ─────────

    @SuppressWarnings("unchecked")
    private static Class<? extends Event> resolveEventClass(String name, Logger logger) {
        try {
            Class<?> clazz = Class.forName(name);
            if (Event.class.isAssignableFrom(clazz)) {
                return (Class<? extends Event>) clazz;
            }
            logger.warning("[ListenerConfig] 类 '" + name + "' 不是 org.bukkit.event.Event 的子类");
        } catch (ClassNotFoundException e) {
            logger.warning("[ListenerConfig] 找不到事件类: " + name);
        }
        return null;
    }

    private static EventPriority resolvePriority(String raw, Logger logger) {
        if (raw == null || raw.isBlank())
            return EventPriority.NORMAL;
        try {
            return EventPriority.valueOf(raw.toUpperCase().trim());
        } catch (IllegalArgumentException e) {
            logger.warning("[ListenerConfig] 无效的优先级 '" + raw + "'，回退到 NORMAL");
            return EventPriority.NORMAL;
        }
    }

    // ───────── 解析结果 ─────────

    /**
     * 已解析的监听器配置（不可变）。
     *
     * @param eventClass      事件类型
     * @param priority        事件优先级
     * @param ignoreCancelled 是否跳过已取消事件
     * @param compilerConfig  编译器原始配置（含 variables + flow）
     */
    public record ListenerConfig(
            Class<? extends Event> eventClass,
            EventPriority priority,
            boolean ignoreCancelled,
            Map<String, Object> compilerConfig) {
    }
}
