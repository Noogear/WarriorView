package cn.warriorView.util.scheduler;

import cn.warriorView.Main;
import cn.warriorView.util.scheduler.task.ITaskWrapper;

public abstract class XRunnable implements Runnable {

    private static IScheduler scheduler;
    private static boolean isInitialized = false;
    protected ITaskWrapper taskWrapper;

    public static void init(Main plugin, boolean isFolia) {
        if (isInitialized) return;
        scheduler = isFolia ? new FoliaScheduler(plugin) : new BukkitScheduler(plugin);
        isInitialized = true;
    }

    public static IScheduler getScheduler() {
        if (!isInitialized) throw new IllegalStateException("XRunnable not initialized");
        return scheduler;
    }

    @Override
    public abstract void run();

    public ITaskWrapper async() {
        checkTaskNotNull();
        return setTaskWrapper(scheduler.async(this));
    }

    public ITaskWrapper async(long delayTicks) {
        checkTaskNotNull();
        return setTaskWrapper(scheduler.asyncLater(this, delayTicks));
    }

    public ITaskWrapper async(long delayTicks, long periodTicks) {
        checkTaskNotNull();
        return setTaskWrapper(scheduler.asyncTimer(this, delayTicks, periodTicks));
    }

    public void cancel() {
        if (this.taskWrapper == null) return;
        this.taskWrapper.cancel();
    }

    public boolean isCancelled() {
        if (this.taskWrapper == null) {
            return true;
        }
        return this.taskWrapper.isCancelled();
    }

    protected ITaskWrapper setTaskWrapper(ITaskWrapper taskWrapper) {
        this.taskWrapper = taskWrapper;
        return this.taskWrapper;
    }

    protected void checkTaskNotNull() {
        if (this.taskWrapper != null) {
            throw new IllegalArgumentException("Runnable is null");
        }
    }

    protected void checkTaskNull() {
        if (this.taskWrapper == null) {
            throw new IllegalArgumentException("Task is null");
        }
    }

}
