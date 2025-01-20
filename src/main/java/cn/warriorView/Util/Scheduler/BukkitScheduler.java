package cn.warriorView.Util.Scheduler;

import cn.warriorView.Main;
import cn.warriorView.Util.Scheduler.task.BukkitTaskWrapper;
import cn.warriorView.Util.Scheduler.task.ITaskWrapper;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;

public class BukkitScheduler implements IScheduler{
    private final Main plugin;

    public BukkitScheduler(Main main) {
        this.plugin = main;
    }

    @Override
    public ITaskWrapper sync(@NotNull Runnable task) {
        return new BukkitTaskWrapper(Bukkit.getScheduler().runTask(plugin, task));
    }

    @Override
    public ITaskWrapper async(@NotNull Runnable task) {
        return new BukkitTaskWrapper(Bukkit.getScheduler().runTaskAsynchronously(plugin, task));
    }

    @Override
    public ITaskWrapper syncLater(@NotNull Runnable task, long delayTicks) {
        return new BukkitTaskWrapper(Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks));
    }

    @Override
    public ITaskWrapper asyncLater(@NotNull Runnable task, long delayTicks) {
        return new BukkitTaskWrapper(Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, task, delayTicks));
    }

    @Override
    public ITaskWrapper syncTimer(@NotNull Runnable task, long delayTicks, long periodTicks) {
        return new BukkitTaskWrapper(Bukkit.getScheduler().runTaskTimer(plugin, task, delayTicks, periodTicks));
    }

    @Override
    public ITaskWrapper asyncTimer(@NotNull Runnable task, long delayTicks, long periodTicks) {
        return new BukkitTaskWrapper(Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, task, delayTicks, periodTicks));
    }

    @Override
    public ITaskWrapper runOnEntity(Entity entity, Runnable task, Runnable retriedTask) {
        return sync(task);
    }

    @Override
    public ITaskWrapper runOnEntityLater(Entity entity, Runnable task, Runnable retriedTask, long delayTicks) {
        return syncLater(task, delayTicks);
    }

    @Override
    public ITaskWrapper runOnEntityTimer(Entity entity, Runnable task, Runnable retriedTask, long delayTicks, long periodTicks) {
        return syncTimer(task, delayTicks, periodTicks);
    }

    @Override
    public ITaskWrapper runOnLocation(Location location, Runnable task) {
        return sync(task);
    }

    @Override
    public ITaskWrapper runOnLocationLater(Location location, Runnable task, long delayTicks) {
        return syncLater(task, delayTicks);
    }

    @Override
    public ITaskWrapper runOnLocationTimer(Location location, Runnable task, long delayTicks, long periodTicks) {
        return syncTimer(task, delayTicks, periodTicks);
    }

    @Override
    public void cancelTasks() {
        Bukkit.getScheduler().cancelTasks(plugin);
    }

}
