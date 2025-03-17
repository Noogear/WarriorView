package cn.warriorView.util.scheduler;

import cn.warriorView.Main;
import cn.warriorView.util.scheduler.task.BukkitTaskWrapper;
import cn.warriorView.util.scheduler.task.ITaskWrapper;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;

public class BukkitScheduler implements IScheduler {
    private final Main plugin;
    private final org.bukkit.scheduler.BukkitScheduler scheduler;

    public BukkitScheduler(Main main) {
        this.plugin = main;
        this.scheduler = Bukkit.getScheduler();
    }

    @Override
    public ITaskWrapper async(@NotNull Runnable task) {
        return new BukkitTaskWrapper(scheduler.runTaskAsynchronously(plugin, task));
    }

    @Override
    public ITaskWrapper asyncLater(@NotNull Runnable task, long delayTicks) {
        return new BukkitTaskWrapper(scheduler.runTaskLaterAsynchronously(plugin, task, delayTicks));
    }

    @Override
    public ITaskWrapper asyncTimer(@NotNull Runnable task, long delayTicks, long periodTicks) {
        return new BukkitTaskWrapper(scheduler.runTaskTimerAsynchronously(plugin, task, delayTicks, periodTicks));
    }

    @Override
    public void cancelTasks() {
        scheduler.cancelTasks(plugin);
    }

}
