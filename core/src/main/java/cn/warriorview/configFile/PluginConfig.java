package cn.warriorview.configFile;

import gloomlib.configuration.api.ConfigurationFile;
import gloomlib.configuration.api.ConfigurationPart;
import gloomlib.configuration.api.annotation.Header;

/**
 * 从 {@code config.yml} 加载插件全局配置。
 *
 * <p>继承 {@link ConfigurationFile}，所有字段由 GloomLib 自动从 YAML 反序列化。</p>
 */
@Header({
        "WarriorView 全局配置",
        "修改后执行 /warriorview reload all 即可生效。"
})
public class PluginConfig extends ConfigurationFile {

    // ── 调度器 ──────────────────────────────────────────────────────────

    public Scheduler scheduler = new Scheduler();

    public static class Scheduler extends ConfigurationPart {
        /** 时间轮大小（必须为 2 的幂次方）。 */
        public int wheelSize = 64;
        /** 对象池容量（必须为 2 的幂次方）。 */
        public int poolCapacity = 1024;
        /** 每 tick 最大执行任务数（限流）。 */
        public int maxTasksPerTick = 500;
        /** 连续空闲多少 tick 后进入休眠。 */
        public int idleThreshold = 600;
    }

    // ── 调试 ────────────────────────────────────────────────────────────

    /** 是否在控制台输出每次重载的耗时。 */
    public boolean timingLog = true;
}
