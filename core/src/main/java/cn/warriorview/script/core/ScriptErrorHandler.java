package cn.warriorview.script.core;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 全局脚本错误拦截器，负责接管脚本运行时的脱敏与日志解耦任务。
 * <p>
 * 默认使用基础的 printStackTrace 和 java.util.logging.Logger，
 * 调用方（如 Bukkit Plugin）可在系统初始化时通过 {@link #setLogger(Logger)} 将框架级 Logger
 * 注入以统一输出渠道。
 */
public final class ScriptErrorHandler {

    private static Logger globalLogger = Logger.getLogger(ScriptErrorHandler.class.getName());

    // Prevent instantiation
    private ScriptErrorHandler() {
    }

    /**
     * 更换全局静态日志器（推荐由插件主类的 onEnable 阶段触发调用）。
     *
     * @param logger 目标框架（如 Bukkit/Paper）的日志器
     */
    public static void setLogger(Logger logger) {
        if (logger != null) {
            globalLogger = logger;
        }
    }

    /**
     * 处理生成的脚本字节码从底层上报的未捕获异常。
     * ！！！此方法由 Script 底层通过 INVOKESTATIC 字节码指令直接反射调用，请勿随意更改方法签名 ！！！
     *
     * @param t         触发的异常
     * @param className 崩溃的生成的底层脚本类的全限定名称
     * @param scriptId  发生错误的脚本的实例级来源 ID
     */
    public static void handleException(Throwable t, String className, String scriptId) {
        globalLogger.log(Level.SEVERE,
                "Script execution error in generated class: " + className + " (Script ID: " + scriptId + ")", t);
    }
}
