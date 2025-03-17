package cn.warriorView.util.scheduler;

import cn.warriorView.Main;
import cn.warriorView.util.scheduler.task.FoliaTaskWrapper;
import cn.warriorView.util.scheduler.task.ITaskWrapper;
import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class FoliaScheduler implements IScheduler {

    private final Main plugin;
    private final AsyncScheduler asyncScheduler;

    public FoliaScheduler(Main main) {
        this.plugin = main;
        this.asyncScheduler = Bukkit.getAsyncScheduler();
    }

    @Override
    public ITaskWrapper async(@NotNull Runnable task) {
        return new FoliaTaskWrapper(asyncScheduler.runNow(plugin, runnableToConsumer(task)));
    }

    @Override
    public ITaskWrapper asyncLater(@NotNull Runnable task, long delayTicks) {
        return new FoliaTaskWrapper(asyncScheduler.runDelayed(plugin, runnableToConsumer(task), delayTicks * 50, TimeUnit.MILLISECONDS));
    }

    @Override
    public ITaskWrapper asyncTimer(@NotNull Runnable task, long delayTicks, long periodTicks) {
        return new FoliaTaskWrapper(asyncScheduler.runAtFixedRate(plugin, runnableToConsumer(task), delayTicks * 50, periodTicks * 50, TimeUnit.MILLISECONDS));
    }

    @Override
    public void cancelTasks() {
        asyncScheduler.cancelTasks(plugin);
    }

    private Consumer<ScheduledTask> runnableToConsumer(Runnable runnable) {
        return (final ScheduledTask task) -> runnable.run();
    }

}
