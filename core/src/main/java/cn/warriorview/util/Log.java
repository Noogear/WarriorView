package cn.warriorview.util;

import org.bukkit.plugin.java.JavaPlugin;
import org.slf4j.Logger;

/**
 * 全局日志枚举单例。
 *
 * <p>在插件 {@code onEnable()} 中调用 {@code Log.init(plugin)} 初始化，
 * 之后所有模块通过 {@code Log.get()} 获取同一个 SLF4J Logger 实例，
 * 无需传递 plugin 引用。</p>
 *
 * <pre>
 *   // 初始化（仅一次）
 *   Log.init(plugin);
 *
 *   // 任意位置使用
 *   Log.info("Loaded {} animations", count);
 *   Log.warn("Missing file: {}", path);
 *   Log.error("Task failed", throwable);
 * </pre>
 */
public enum Log {
    ;

    private static Logger logger;

    /** 绑定到插件的 ComponentLogger（实现 SLF4J Logger）。 */
    public static void init(JavaPlugin plugin) {
        logger = plugin.getComponentLogger();
    }

    /** 获取底层 SLF4J Logger 实例。 */
    public static Logger get() {
        return logger;
    }

    // ── 便捷方法 ─────────────────────────────────────────────────────────

    public static void info(String msg)                        { logger.info(msg); }
    public static void info(String fmt, Object arg)            { logger.info(fmt, arg); }
    public static void info(String fmt, Object a, Object b)    { logger.info(fmt, a, b); }
    public static void info(String fmt, Object... args)        { logger.info(fmt, args); }

    public static void warn(String msg)                        { logger.warn(msg); }
    public static void warn(String fmt, Object arg)            { logger.warn(fmt, arg); }
    public static void warn(String fmt, Object a, Object b)    { logger.warn(fmt, a, b); }
    public static void warn(String fmt, Object... args)        { logger.warn(fmt, args); }

    public static void error(String msg)                       { logger.error(msg); }
    public static void error(String msg, Throwable t)          { logger.error(msg, t); }
    public static void error(String fmt, Object arg)           { logger.error(fmt, arg); }
    public static void error(String fmt, Object a, Object b)   { logger.error(fmt, a, b); }
}
