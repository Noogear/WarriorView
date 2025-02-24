package cn.warriorView.Util.Scheduler;

import cn.warriorView.Main;
import cn.warriorView.Util.Scheduler.task.BukkitTaskWrapper;
import cn.warriorView.Util.Scheduler.task.ITaskWrapper;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;

public class BukkitScheduler implements IScheduler {
    private final Main plugin;

    public BukkitScheduler(Main main) {
        this.plugin = main;
    }

    @Override
    public ITaskWrapper async(@NotNull Runnable task) {
        return new BukkitTaskWrapper(Bukkit.getScheduler().runTaskAsynchronously(plugin, task));
    }

    @Override
    public ITaskWrapper asyncLater(@NotNull Runnable task, long delayTicks) {
        return new BukkitTaskWrapper(Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, task, delayTicks));
    }

    @Override
    public ITaskWrapper asyncTimer(@NotNull Runnable task, long delayTicks, long periodTicks) {
        return new BukkitTaskWrapper(Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, task, delayTicks, periodTicks));
    }

    @Override
    public void cancelTasks() {
        Bukkit.getScheduler().cancelTasks(plugin);
    }

}
