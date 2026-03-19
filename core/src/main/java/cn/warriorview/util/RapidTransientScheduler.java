package cn.warriorview.util;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 高性能无锁瞬态任务调度器，基于时间轮 (Timing Wheel) + 对象池实现。
 * <p>
 * 专为大量、短生命周期的游戏任务设计，极低 GC 压力与调度开销。
 * 支持一次性任务、延迟任务、周期任务，以及不参与空闲休眠计数的守护任务。
 */
public final class RapidTransientScheduler {

    private static final int STATE_STOPPED = 0;
    private static final int STATE_RUNNING = 1;
    private static final VarHandle WHEEL_VH;
    private static final VarHandle POOL_VH;
    private static final VarHandle STATE_VH;
    private static final VarHandle BACKLOG_HEAD_VH;
    private static final VarHandle TICK_VH;
    private static final VarHandle TOTAL_COUNT_VH;
    private static final VarHandle DRIVER_VH;
    private static final VarHandle POOL_ROVER_VH;
    private static final VarHandle IDLE_TICKS_VH;
    
    // 守护任务链表头指针的 VarHandle
    private static final VarHandle DAEMON_HEAD_VH;

    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            WHEEL_VH = MethodHandles.arrayElementVarHandle(TransientTask[].class);
            POOL_VH = MethodHandles.arrayElementVarHandle(TransientTask[].class);
            STATE_VH = l.findVarHandle(RapidTransientScheduler.class, "runState", int.class);
            BACKLOG_HEAD_VH = l.findVarHandle(RapidTransientScheduler.class, "backlogHead", TransientTask.class);
            TICK_VH = l.findVarHandle(RapidTransientScheduler.class, "currentTick", long.class);
            TOTAL_COUNT_VH = l.findVarHandle(RapidTransientScheduler.class, "totalTaskCount", int.class);
            DRIVER_VH = l.findVarHandle(RapidTransientScheduler.class, "driverTask", ScheduledTask.class);
            POOL_ROVER_VH = l.findVarHandle(RapidTransientScheduler.class, "poolRover", int.class);
            IDLE_TICKS_VH = l.findVarHandle(RapidTransientScheduler.class, "idleTicks", int.class);
            DAEMON_HEAD_VH = l.findVarHandle(RapidTransientScheduler.class, "daemonHead", DaemonTask.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final JavaPlugin plugin;
    private final int wheelMask;
    private final int poolMask;
    private final int maxTasksPerTick;
    private final int idleThreshold;
    private final TransientTask[] wheel;
    private final TransientTask[] pool;

    @SuppressWarnings("FieldMayBeFinal")
    private volatile long currentTick = 0;
    @SuppressWarnings("FieldMayBeFinal")
    private volatile int totalTaskCount = 0;
    @SuppressWarnings("FieldMayBeFinal")
    private volatile int runState = STATE_STOPPED;
    @SuppressWarnings("FieldMayBeFinal")
    private volatile int poolRover = 0;
    @SuppressWarnings("FieldMayBeFinal")
    private volatile TransientTask backlogHead = null;
    @SuppressWarnings("FieldMayBeFinal")
    private volatile int idleTicks = 0;
    @SuppressWarnings("FieldMayBeFinal")
    private volatile ScheduledTask driverTask = null;
    
    // 守护任务无锁单向链表头指针
    @SuppressWarnings("FieldMayBeFinal")
    private volatile DaemonTask daemonHead = null;

    private volatile Runnable postTickHook;

    /**
     * 使用默认参数初始化调度器。
     * <p>(wheelSize=64, poolCapacity=1024, maxTasksPerTick=500, idleThreshold=600)</p>
     *
     * @param plugin 插件实例
     */
    public RapidTransientScheduler(JavaPlugin plugin) {
        this(plugin, 64, 1024, 500, 600);
    }

    /**
     * 自定义配置初始化调度器
     *
     * @param plugin          插件实例
     * @param wheelSize       时间轮大小 (需为 2 的幂次方)
     * @param poolCapacity    对象池容量 (需为 2 的幂次方)
     * @param maxTasksPerTick 每 Tick 最大执行数 (限流)
     * @param idleThreshold   空闲多少 Tick 后休眠
     */
    public RapidTransientScheduler(JavaPlugin plugin, int wheelSize, int poolCapacity, int maxTasksPerTick,
            int idleThreshold) {
        if (Integer.bitCount(wheelSize) != 1) {
            throw new IllegalArgumentException("wheelSize must be a power of 2");
        }
        if (Integer.bitCount(poolCapacity) != 1) {
            throw new IllegalArgumentException("poolCapacity must be a power of 2");
        }
        this.plugin = Objects.requireNonNull(plugin);
        this.maxTasksPerTick = maxTasksPerTick;
        this.idleThreshold = idleThreshold;
        this.wheel = new TransientTask[wheelSize];
        this.pool = new TransientTask[poolCapacity];
        this.wheelMask = wheelSize - 1;
        this.poolMask = poolCapacity - 1;
    }

    /**
     * 添加一个长期的守护任务 (默认每 1 Tick 执行一次)。
     *
     * @param task 要执行的守护任务
     * @return 任务控制句柄
     */
    public TaskHandle dispatchDaemon(Runnable task) {
        return dispatchDaemon(task, 1, 1);
    }

    /**
     * 添加一个长期的守护任务，支持初始延迟与执行间隔。
     * <p>
     * 守护任务在每 Tick 最优先执行，但不参与空闲计数，
     * 不会阻止调度器休眠，也不会在调度器休眠时主动唤醒它。
     *
     * @param task   要执行的守护任务
     * @param delay  初始延迟 Tick 数（基于调度器活跃 Tick，≤0 视为 1）
     * @param period 执行周期间隔 Tick 数（≤0 视为 1）
     * @return 任务控制句柄
     */
    public TaskHandle dispatchDaemon(Runnable task, long delay, long period) {
        long actualDelay = delay <= 0 ? 1 : delay;
        long actualPeriod = period <= 0 ? 1 : period;
        // 目标时间 = 当前已流逝活跃 Tick + 延迟
        long target = (long) TICK_VH.getOpaque(this) + actualDelay;

        DaemonTask newDaemon = new DaemonTask(task, actualPeriod, target);
        DaemonTask oldHead;
        
        // 无锁 CAS 压入链表头 (Treiber Stack 算法)
        do {
            oldHead = (DaemonTask) DAEMON_HEAD_VH.getVolatile(this);
            newDaemon.next = oldHead;
        } while (!DAEMON_HEAD_VH.compareAndSet(this, oldHead, newDaemon));
        
        return newDaemon;
    }

    /**
     * 在下一 Tick 立即执行一次性任务。
     *
     * @param task 要执行的任务
     * @return 任务控制句柄
     */
    public TaskHandle dispatchNow(Runnable task) {
        return schedule(task, -1, (long) TICK_VH.getOpaque(this) + 1, 1);
    }

    /**
     * 延迟指定 Tick 数后执行一次性任务。
     *
     * @param task  要执行的任务
     * @param ticks 延迟 Tick 数，≤0 时等同于 {@link #dispatchNow(Runnable)}
     * @return 任务控制句柄
     */
    public TaskHandle dispatchLater(Runnable task, long ticks) {
        if (ticks <= 0) {
            return dispatchNow(task);
        }
        return schedule(task, -1, (long) TICK_VH.getOpaque(this) + ticks, ticks);
    }

    /**
     * 按固定周期循环执行任务。
     *
     * @param task   要执行的任务
     * @param delay  首次执行前的延迟 Tick 数，≤0 视为 1
     * @param period 执行周期间隔 Tick 数，必须 &gt; 0
     * @return 任务控制句柄
     */
    public TaskHandle dispatchTimer(Runnable task, long delay, long period) {
        if (period <= 0) {
            throw new IllegalArgumentException("period must be greater than 0");
        }
        long actualDelay = delay <= 0 ? 1 : delay;
        long start = (long) TICK_VH.getOpaque(this) + actualDelay;
        return schedule(task, period, start, actualDelay);
    }

    /**
     * 设置 post-tick 钩子：在每次 {@link #tick()} 处理完所有到期任务后、
     * 空闲检测前同步调用。适用于包收集器 flush 等需要等待同 tick 所有
     * 任务执行完毕的批处理操作。
     *
     * @param hook 钩子回调，{@code null} 移除
     */
    public void setPostTickHook(Runnable hook) {
        this.postTickHook = hook;
    }

    /**
     * 关闭并清理所有任务
     */
    public void shutdown() {
        if (STATE_VH.compareAndSet(this, STATE_RUNNING, STATE_STOPPED)) {
            ScheduledTask currentDriver = (ScheduledTask) DRIVER_VH.getAndSet(this, null);
            if (currentDriver != null) {
                currentDriver.cancel();
            }
            clearAll();
        }
    }

    private TaskHandle schedule(Runnable command, long period, long target, long delay) {
        TransientTask task = acquireTask();
        if (task == null) {
            task = new TransientTask();
        }

        task.init(command, period, target);

        int slot = (int) (((long) TICK_VH.getOpaque(this) + delay) & wheelMask);
        pushToWheel(slot, task);

        TOTAL_COUNT_VH.getAndAdd(this, 1);
        ensureStarted();
        IDLE_TICKS_VH.setVolatile(this, 0);
        return task;
    }

    private void ensureStarted() {
        if ((int) STATE_VH.getAcquire(this) == STATE_RUNNING) {
            return;
        }
        if (STATE_VH.compareAndSet(this, STATE_STOPPED, STATE_RUNNING)) {
            ScheduledTask newTask = plugin.getServer().getAsyncScheduler().runAtFixedRate(
                    plugin,
                    (task) -> this.tick(),
                    50L, 50L, TimeUnit.MILLISECONDS);
            if (!DRIVER_VH.compareAndSet(this, null, newTask)) {
                newTask.cancel();
            }
        }
    }

    private void tick() {
        // 统一提取当前时间 (now)，对时间轮和守护任务采用一致的基准
        long now = (long) TICK_VH.getAndAdd(this, 1L) + 1;

        // 1. 优先处理并执行所有长期守护任务
        processDaemons(now);

        // 2. 时间轮槽位与积压任务处理
        int slot = (int) (now & wheelMask);
        int quota = maxTasksPerTick;

        TransientTask backlog = (TransientTask) BACKLOG_HEAD_VH.getAndSet(this, null);
        if (backlog != null) {
            quota = processChain(backlog, now, quota);
        }

        if (quota > 0) {
            TransientTask slotChain = (TransientTask) WHEEL_VH.getAndSet(wheel, slot, null);
            if (slotChain != null) {
                processChain(slotChain, now, quota);
            }
        } else {
            TransientTask pending = (TransientTask) WHEEL_VH.getAndSet(wheel, slot, null);
            if (pending != null) {
                chainPushBacklog(pending);
            }
        }

        Runnable hook = postTickHook;
        if (hook != null) hook.run();

        // 3. 休眠检测 (守护任务绝对不计入总数判断，保障休眠策略不变)
        if (quota < maxTasksPerTick || (int) TOTAL_COUNT_VH.getOpaque(this) > 0
                || BACKLOG_HEAD_VH.getOpaque(this) != null) {
            IDLE_TICKS_VH.setVolatile(this, 0);
        } else if ((int) IDLE_TICKS_VH.getAndAdd(this, 1) + 1 >= idleThreshold) {
            trySleep();
        }
    }

    // ==========================================
    // 守护任务处理
    // ==========================================
    private void processDaemons(long now) {
        DaemonTask curr = (DaemonTask) DAEMON_HEAD_VH.getVolatile(this);
        DaemonTask prev = null;

        while (curr != null) {
            DaemonTask next = curr.next;

            if (curr.cancelled) {
                if (prev == null) {
                    // 头节点取消，CAS 摘除；若失败说明有新节点并发插入到头部，
                    // 将其保留为中间节点，下一 Tick 会从 else 分支安全摘除。
                    if (!DAEMON_HEAD_VH.compareAndSet(this, curr, next)) {
                        prev = curr;
                    }
                } else {
                    // 中间节点由单线程遍历，直接修改 prev.next 即可
                    prev.next = next;
                }
            } else {
                if (now >= curr.targetTick) {
                    try {
                        curr.task.run();
                    } catch (Throwable t) {
                        Log.error("Daemon task execution exception", t);
                    }
                    // 更新下一次的目标时间（基于当前活跃时间锚定，防休眠后追赶风暴）
                    curr.targetTick = now + curr.period;
                }
                prev = curr;
            }
            curr = next;
        }
    }

    private int processChain(TransientTask head, long now, int quota) {
        TransientTask curr = head;
        while (curr != null && quota > 0) {
            TransientTask next = curr.next;
            curr.next = null;
            if (processTask(curr, now)) {
                quota--;
            }
            curr = next;
        }
        if (curr != null) {
            chainPushBacklog(curr);
        }
        return quota;
    }

    private boolean processTask(TransientTask task, long now) {
        if (task.isCancelled()) {
            finalizeTask(task);
            return false;
        }

        if (now < task.targetTick) {
            pushToWheel((int) (task.targetTick & wheelMask), task);
            return false;
        }

        boolean error = false;
        try {
            task.task.run();
        } catch (Throwable t) {
            error = true;
            Log.error("Task execution exception", t);
        }

        if (task.period > 0 && !task.isCancelled() && !error) {
            task.targetTick = now + task.period;
            pushToWheel((int) (task.targetTick & wheelMask), task);
        } else {
            finalizeTask(task);
        }
        return true;
    }

    private void pushToWheel(int slot, TransientTask task) {
        TransientTask oldHead;
        do {
            oldHead = (TransientTask) WHEEL_VH.getVolatile(wheel, slot);
            task.next = oldHead;
        } while (!WHEEL_VH.compareAndSet(wheel, slot, oldHead, task));
    }

    private void chainPushBacklog(TransientTask chainHead) {
        TransientTask tail = chainHead;
        while (tail.next != null) {
            tail = tail.next;
        }

        TransientTask oldHead;
        do {
            oldHead = (TransientTask) BACKLOG_HEAD_VH.getVolatile(this);
            tail.next = oldHead;
        } while (!BACKLOG_HEAD_VH.compareAndSet(this, oldHead, chainHead));
    }

    private TransientTask acquireTask() {
        int start = (int) POOL_ROVER_VH.getAndAdd(this, 1) & poolMask;
        for (int i = 0; i < 4; i++) {
            int idx = (start + i) & poolMask;
            TransientTask t = (TransientTask) POOL_VH.getAndSet(pool, idx, null);
            if (t != null) {
                return t;
            }
        }
        return null;
    }

    private void finalizeTask(TransientTask task) {
        TOTAL_COUNT_VH.getAndAdd(this, -1);
        task.reset();
        int start = (int) POOL_ROVER_VH.getAndAdd(this, 1) & poolMask;
        for (int i = 0; i < 4; i++) {
            int idx = (start + i) & poolMask;
            if (POOL_VH.compareAndSet(pool, idx, null, task)) {
                return;
            }
        }
    }

    private void trySleep() {
        if ((int) TOTAL_COUNT_VH.getOpaque(this) > 0 || BACKLOG_HEAD_VH.getOpaque(this) != null) {
            IDLE_TICKS_VH.setVolatile(this, 0);
            return;
        }

        if (STATE_VH.compareAndSet(this, STATE_RUNNING, STATE_STOPPED)) {
            ScheduledTask oldTask = (ScheduledTask) DRIVER_VH.getAndSet(this, null);
            if (oldTask != null) {
                oldTask.cancel();
            }

            // 二次验证防并发边缘情况
            if ((int) TOTAL_COUNT_VH.getOpaque(this) > 0 || BACKLOG_HEAD_VH.getOpaque(this) != null) {
                ensureStarted();
            }
        }
    }

    private void clearAll() {
        BACKLOG_HEAD_VH.setRelease(this, null);
        TOTAL_COUNT_VH.setRelease(this, 0);
        DAEMON_HEAD_VH.setRelease(this, null); // 释放守护任务链表，防止内存泄漏
        for (int i = 0; i < wheel.length; i++) {
            WHEEL_VH.setRelease(wheel, i, null);
        }
        for (int i = 0; i < pool.length; i++) {
            POOL_VH.setRelease(pool, i, null);
        }
    }

    /**
     * 任务控制句柄，用于取消排队或循环中的任务
     */
    public interface TaskHandle {
        /**
         * 取消并移除此任务
         */
        void cancel();

        /**
         * 检查任务是否已被取消
         */
        boolean isCancelled();
    }

    private static final class TransientTask implements TaskHandle {
        TransientTask next;
        Runnable task;
        long period;
        long targetTick;
        volatile boolean cancelled;

        void init(Runnable task, long period, long targetTick) {
            this.task = task;
            this.period = period;
            this.targetTick = targetTick;
            this.cancelled = false;
        }

        void reset() {
            this.task = null;
            this.period = 0;
            this.targetTick = 0;
            this.cancelled = false;
            this.next = null;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }
    }

    private static final class DaemonTask implements TaskHandle {
        final Runnable task;
        final long period;
        long targetTick;
        volatile boolean cancelled;
        DaemonTask next;

        DaemonTask(Runnable task, long period, long targetTick) {
            this.task = task;
            this.period = period;
            this.targetTick = targetTick;
            this.cancelled = false;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }
    }
}