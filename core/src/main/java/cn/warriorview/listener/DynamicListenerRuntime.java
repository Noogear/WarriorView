package cn.warriorview.listener;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * 动态监听器运行时。
 * 管理编译→加载→注册→采样→重编译→热替换的完整生命周期。
 * 配置解析委托给 {@link cn.warriorview.listener.ListenerConfigParser}。
 *
 * @see cn.warriorview.listener.AsmLogicCompiler
 * @see cn.warriorview.listener.GeneratedLogic
 */
public final class DynamicListenerRuntime {

    private static final int WARMUP_THRESHOLD = 10_000;
    private static final String GENERATED_LOGIC_INTERNAL = GeneratedLogic.class.getName().replace('.', '/');
    private static final AtomicInteger classCounter = new AtomicInteger(0);
    private final Logger logger;

    public DynamicListenerRuntime(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger 不能为 null");
    }

    // ═══════════════════════════════════════════
    // 公共注册入口
    // ═══════════════════════════════════════════

    /**
     * 从 {@link org.bukkit.configuration.ConfigurationSection} 注册监听器。
     *
     * @param section    YAML 配置节
     * @param plugin     插件实例
     * @param listenerId 唯一标识
     * @param actionUtil Action 工具类全限定名
     */
    public ListenerHandle register(ConfigurationSection section,
            Plugin plugin, String listenerId, String actionUtil) {
        ListenerConfigParser.ListenerConfig config = ListenerConfigParser.parse(section, listenerId, logger);
        if (config == null)
            return ListenerHandle.EMPTY;
        return registerInternal(config, plugin, listenerId, actionUtil);
    }

    /**
     * 从原始 {@link java.util.Map} 注册监听器。
     *
     * @param rawConfig  原始 Map 配置（含 event / variables / flow）
     * @param plugin     插件实例
     * @param listenerId 唯一标识
     * @param actionUtil Action 工具类全限定名
     */
    public ListenerHandle register(Map<String, Object> rawConfig,
            Plugin plugin, String listenerId, String actionUtil) {
        ListenerConfigParser.ListenerConfig config = ListenerConfigParser.parseFromMap(rawConfig, listenerId, logger);
        if (config == null)
            return ListenerHandle.EMPTY;
        return registerInternal(config, plugin, listenerId, actionUtil);
    }

    /**
     * 显式参数注册（不经过配置解析）。
     *
     * @param compilerConfig  编译器配置（含 variables + flow）
     * @param eventClass      事件类型
     * @param plugin          插件实例
     * @param priority        事件优先级
     * @param ignoreCancelled 是否跳过已取消事件
     * @param listenerId      唯一标识
     * @param actionUtil      Action 工具类全限定名
     */
    public <E extends Event> ListenerHandle register(
            Map<String, Object> compilerConfig,
            Class<E> eventClass,
            Plugin plugin,
            EventPriority priority,
            boolean ignoreCancelled,
            String listenerId,
            String actionUtil) {
        var config = new ListenerConfigParser.ListenerConfig(
                eventClass, priority, ignoreCancelled, compilerConfig);
        return registerInternal(config, plugin, listenerId, actionUtil);
    }

    // ═══════════════════════════════════════════
    // 内部注册实现
    // ═══════════════════════════════════════════

    private ListenerHandle registerInternal(
            ListenerConfigParser.ListenerConfig config,
            Plugin plugin,
            String listenerId,
            String actionUtil) {

        Class<? extends Event> eventClass = config.eventClass();
        EventPriority priority = config.priority();
        boolean ignoreCancelled = config.ignoreCancelled();

        String className = "cn.warriorview.generated.Logic_" + listenerId + "_"
                + classCounter.getAndIncrement();
        String actionUtilInternal = actionUtil.replace('.', '/');

        // 第一阶段：采样编译
        int checkCount = countChecks(config.compilerConfig());
        LongAdder[] counters = new LongAdder[checkCount];
        for (int i = 0; i < checkCount; i++)
            counters[i] = new LongAdder();

        GeneratedLogic profilingLogic = compileAndLoad(
                config.compilerConfig(), eventClass, className + "_Profiling",
                true, counters, actionUtilInternal);

        if (profilingLogic == null) {
            logger.warning("[DynamicListener] 编译失败，监听器 '" + listenerId + "' 未注册");
            return ListenerHandle.EMPTY;
        }

        ListenerHandle handle = new ListenerHandle();
        handle.logic = profilingLogic;

        Listener listener = new Listener() {
        };
        handle.listener = listener;

        AtomicInteger triggerCount = new AtomicInteger(0);
        boolean cancellable = Cancellable.class.isAssignableFrom(eventClass);

        Bukkit.getPluginManager().registerEvent(eventClass, listener, priority, (l, event) -> {
            if (!eventClass.isInstance(event))
                return;
            if (ignoreCancelled && cancellable && ((Cancellable) event).isCancelled())
                return;

            try {
                handle.logic.execute(event);
            } catch (Throwable t) {
                logger.log(Level.SEVERE, "[DynamicListener] 事件处理异常: " + listenerId, t);
                return;
            }

            if (!handle.optimized) {
                int count = triggerCount.incrementAndGet();
                if (count >= WARMUP_THRESHOLD) {
                    recompileAsync(handle, config.compilerConfig(), eventClass,
                            className + "_Optimized", counters, actionUtilInternal, listenerId);
                }
            }
        }, plugin);

        logger.info("[DynamicListener] 监听器 '" + listenerId
                + "' 已注册（事件=" + eventClass.getSimpleName()
                + "，优先级=" + priority
                + "，忽略取消=" + ignoreCancelled
                + "，采样阈值=" + WARMUP_THRESHOLD + "）");
        return handle;
    }

    // ═══════════════════════════════════════════
    // PGO 重编译
    // ═══════════════════════════════════════════

    /** 采样达标后异步重编译为零开销版本并热替换 */
    private <E extends Event> void recompileAsync(
            ListenerHandle handle,
            Map<String, Object> compilerConfig,
            Class<E> eventClass,
            String optimizedClassName,
            LongAdder[] counters,
            String actionUtilInternal,
            String listenerId) {

        synchronized (handle) {
            if (handle.optimized)
                return;
            handle.optimized = true;
        }

        logger.info("[DynamicListener] 监听器 '" + listenerId + "' 正在根据采样数据重编译最优路径...");

        GeneratedLogic optimizedLogic = compileAndLoad(
                compilerConfig, eventClass, optimizedClassName,
                false, counters, actionUtilInternal);

        if (optimizedLogic != null) {
            handle.logic = optimizedLogic;
            logger.info("[DynamicListener] 监听器 '" + listenerId
                    + "' 最优路径已部署。计数器已移除，零开销运行。");
        } else {
            logger.warning("[DynamicListener] 监听器 '" + listenerId + "' 重编译失败，继续使用采样版本");
            handle.optimized = false;
        }
    }

    // ═══════════════════════════════════════════
    // 编译 + 加载流水线
    // ═══════════════════════════════════════════

    /**
     * 编译→加载→实例化→注入静态字段。
     * 使用 {@link java.lang.invoke.MethodHandles.Lookup#defineHiddenClass} 加载字节码，
     * Hidden Class 无需独立 ClassLoader 即可被 GC 回收。
     */
    private GeneratedLogic compileAndLoad(
            Map<String, Object> compilerConfig,
            Class<?> eventClass,
            String className,
            boolean profiling,
            LongAdder[] counters,
            String actionUtilInternal) {
        try {
            AsmLogicCompiler compiler = new AsmLogicCompiler(
                    compilerConfig, eventClass, className,
                    profiling, counters,
                    GENERATED_LOGIC_INTERNAL, actionUtilInternal);

            byte[] bytecode = compiler.compile();

            // Hidden Class：JVM 原生一次性类，无需自建 ClassLoader，被替换后自动 GC
            Class<?> clazz = MethodHandles.lookup()
                    .defineHiddenClass(bytecode, true)
                    .lookupClass();

            if (profiling && counters != null) {
                injectStaticField(clazz, "COUNTERS", counters);
            }

            Map<String, String> regexMap = compiler.getRegexFieldMap();
            for (Map.Entry<String, String> entry : regexMap.entrySet()) {
                injectStaticField(clazz, entry.getValue(), Pattern.compile(entry.getKey()));
            }

            return (GeneratedLogic) clazz.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            logger.log(Level.SEVERE, "[DynamicListener] 编译或加载失败: " + className, e);
            return null;
        }
    }

    private static void injectStaticField(Class<?> clazz, String fieldName, Object value) {
        try {
            Field field = clazz.getField(fieldName);
            field.set(null, value);
        } catch (NoSuchFieldException ignored) {
        } catch (IllegalAccessException e) {
            throw new RuntimeException("无法注入静态字段: " + fieldName, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static int countChecks(Map<String, Object> config) {
        var flow = (java.util.List<Map<String, Object>>) config.get("flow");
        if (flow == null)
            return 0;
        return (int) flow.stream().filter(n -> "check".equals(n.get("type"))).count();
    }

    // ═══════════════════════════════════════════
    // 监听器句柄
    // ═══════════════════════════════════════════

    /** 监听器句柄：卸载 + 状态查询 */
    public static final class ListenerHandle {
        static final ListenerHandle EMPTY = new ListenerHandle();

        volatile GeneratedLogic logic;
        volatile boolean optimized;
        Listener listener;

        /** 卸载此监听器 */
        public void unregister() {
            if (listener != null) {
                HandlerList.unregisterAll(listener);
                listener = null;
            }
            logic = null;
        }

        /** 是否已完成 PGO 优化 */
        public boolean isOptimized() {
            return optimized;
        }
    }
}
