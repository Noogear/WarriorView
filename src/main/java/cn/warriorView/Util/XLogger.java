package cn.warriorView.Util;

import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

import java.util.logging.Logger;

public class XLogger {
    public static XLogger instance;
    private final Logger logger;

    public XLogger() {
        instance = this;
        this.logger = Logger.getLogger("WarriorView");
    }

    public XLogger(@Nullable JavaPlugin plugin) {
        instance = this;
        this.logger = plugin != null ? plugin.getLogger() : Logger.getLogger("WarriorView");
    }

    public static void info(String message) {
        instance.logger.info(message);
    }

    public static void info(String message, Object... args) {
        instance.logger.info(String.format(message, args));
    }

    public static void warn(String message) {
        instance.logger.warning(message);
    }

    public static void warn(String message, Object... args) {
        instance.logger.warning(String.format(message, args));
    }

    public static void err(String message) {
        instance.logger.severe(message);
    }

    public static void err(String message, Object... args) {
        instance.logger.severe(String.format(message, args));
    }
}
