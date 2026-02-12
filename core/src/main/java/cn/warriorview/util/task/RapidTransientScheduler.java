package cn.warriorview.util.task;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.plugin.java.JavaPlugin;
import org.slf4j.Logger;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public final class RapidTransientScheduler {

    private static final int STATE_STOPPED = 0;
    private static final int STATE_RUNNING = 1;
    private static final VarHandle WHEEL_VH;
    private static final VarHandle POOL_VH;
    private static final VarHandle STATE_VH;
    private static final VarHandle BACKLOG_HEAD_VH;
    private static final VarHandle BACKLOG_SIZE_VH;
    private static final VarHandle TICK_VH;
    private static final VarHandle TOTAL_COUNT_VH;
    private static final VarHandle DRIVER_VH;
    private static final VarHandle POOL_ROVER_VH;
    private static final VarHandle IDLE_TICKS_VH;

    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            WHEEL_VH = MethodHandles.arrayElementVarHandle(TransientTask[].class);
            POOL_VH = MethodHandles.arrayElementVarHandle(TransientTask[].class);
            STATE_VH = l.findVarHandle(RapidTransientScheduler.class, "runState", int.class);
            BACKLOG_HEAD_VH = l.findVarHandle(RapidTransientScheduler.class, "backlogHead", TransientTask.class);
            BACKLOG_SIZE_VH = l.findVarHandle(RapidTransientScheduler.class, "backlogSize", int.class);
            TICK_VH = l.findVarHandle(RapidTransientScheduler.class, "currentTick", long.class);
            TOTAL_COUNT_VH = l.findVarHandle(RapidTransientScheduler.class, "totalTaskCount", int.class);
            DRIVER_VH = l.findVarHandle(RapidTransientScheduler.class, "driverTask", ScheduledTask.class);
            POOL_ROVER_VH = l.findVarHandle(RapidTransientScheduler.class, "poolRover", int.class);
            IDLE_TICKS_VH = l.findVarHandle(RapidTransientScheduler.class, "idleTicks", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final JavaPlugin plugin;
    private final Config config;
    private final Logger logger;
    private final int wheelMask;
    private final int poolMask;
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
    private volatile int backlogSize = 0;
    @SuppressWarnings("FieldMayBeFinal")
    private volatile int idleTicks = 0;
    @SuppressWarnings("FieldMayBeFinal")
    private volatile ScheduledTask driverTask = null;


    public RapidTransientScheduler(Config config, JavaPlugin plugin) {
        this.config = Objects.requireNonNull(config);
        this.plugin = Objects.requireNonNull(plugin);
        this.logger = plugin.getComponentLogger();
        this.wheel = new TransientTask[config.wheelSize];
        this.pool = new TransientTask[config.poolCapacity];
        this.wheelMask = config.wheelSize - 1;
        this.poolMask = config.poolCapacity - 1;
    }

    public TaskHandle dispatchNow(Runnable task) {
        return schedule(task, -1, 0, 0);
    }

    public TaskHandle dispatchLater(Runnable task, long ticks) {
        if (ticks <= 0) {
            return dispatchNow(task);
        }
        return schedule(task, -1, (long) TICK_VH.getOpaque(this) + ticks, ticks);
    }

    public TaskHandle dispatchTimer(Runnable task, long delay, long period) {
        if (period <= 0) {
            throw new IllegalArgumentException("周期必须大于 0");
        }
        long start = (long) TICK_VH.getOpaque(this) + (delay < 0 ? 0 : delay);
        return schedule(task, period, start, delay);
    }

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
                    50L, 50L, TimeUnit.MILLISECONDS
            );
            if (!DRIVER_VH.compareAndSet(this, null, newTask)) {
                newTask.cancel();
            }
        }
    }

    private void tick() {
        long now = (long) TICK_VH.getAndAdd(this, 1L) + 1;
        int slot = (int) (now & wheelMask);
        int quota = config.maxTasksPerTick;

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

        if (quota < config.maxTasksPerTick || (int) TOTAL_COUNT_VH.getOpaque(this) > 0 || BACKLOG_HEAD_VH.getOpaque(this) != null) {
            IDLE_TICKS_VH.setVolatile(this, 0);
        } else if ((int) IDLE_TICKS_VH.getAndAdd(this, 1) + 1 >= config.idleThreshold) {
            trySleep();
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
            task.command.run();
        } catch (Throwable t) {
            error = true;
            logger.error("任务执行异常", t);
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
        int count = 1;
        while (tail.next != null) {
            tail = tail.next;
            count++;
        }

        TransientTask oldHead;
        do {
            oldHead = (TransientTask) BACKLOG_HEAD_VH.getVolatile(this);
            tail.next = oldHead;
        } while (!BACKLOG_HEAD_VH.compareAndSet(this, oldHead, chainHead));
        BACKLOG_SIZE_VH.getAndAdd(this, count);
    }

    private void addToBacklog(TransientTask task) {
        if ((int) BACKLOG_SIZE_VH.getOpaque(this) >= config.maxBacklogSize) {
            finalizeTask(task);
            return;
        }

        TransientTask oldHead;
        do {
            oldHead = (TransientTask) BACKLOG_HEAD_VH.getVolatile(this);
            task.next = oldHead;
        } while (!BACKLOG_HEAD_VH.compareAndSet(this, oldHead, task));
        BACKLOG_SIZE_VH.getAndAdd(this, 1);
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
            if ((int) TOTAL_COUNT_VH.getOpaque(this) > 0 || BACKLOG_HEAD_VH.getOpaque(this) != null) {
                runState = STATE_RUNNING;
                return;
            }
            ScheduledTask task = (ScheduledTask) DRIVER_VH.getAndSet(this, null);
            if (task != null) {
                task.cancel();
            }
        }
    }

    private void clearAll() {
        BACKLOG_HEAD_VH.setRelease(this, null);
        BACKLOG_SIZE_VH.setRelease(this, 0);
        TOTAL_COUNT_VH.setRelease(this, 0);
        for (int i = 0; i < config.wheelSize; i++) {
            WHEEL_VH.setRelease(wheel, i, null);
        }
        for (int i = 0; i < config.poolCapacity; i++) {
            POOL_VH.setRelease(pool, i, null);
        }
    }

    public interface TaskHandle {
        void cancel();

        boolean isCancelled();
    }

    public record Config(
            int wheelSize,
            int poolCapacity,
            int maxTasksPerTick,
            int maxBacklogSize,
            int idleThreshold
    ) {
        public static final Config DEFAULT = new Config(64, 1024, 500, 10000, 600);

        public Config {
            if (Integer.bitCount(wheelSize) != 1) {
                throw new IllegalArgumentException("wheelSize 必须是 2 的冪");
            }
            if (Integer.bitCount(poolCapacity) != 1) {
                throw new IllegalArgumentException("poolCapacity 必须是 2 的冪");
            }
        }
    }

    private static final class TransientTask implements TaskHandle {
        TransientTask next;
        Runnable command;
        long period;
        long targetTick;
        volatile boolean cancelled;

        void init(Runnable c, long p, long t) {
            this.command = c;
            this.period = p;
            this.targetTick = t;
            this.cancelled = false;
        }

        void reset() {
            this.command = null;
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
}