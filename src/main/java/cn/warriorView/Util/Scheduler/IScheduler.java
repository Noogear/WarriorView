package cn.warriorView.util.scheduler;

import cn.warriorView.util.scheduler.task.ITaskWrapper;
import org.jetbrains.annotations.NotNull;

/**
 * 平台调度器接口
 */
public interface IScheduler {

    /**
     * 异步执行一个任务
     *
     * @param task 需要执行的任务
     */
    ITaskWrapper async(@NotNull Runnable task);

    /**
     * 在延迟后异步执行一个任务
     *
     * @param task       需要执行的任务
     * @param delayTicks 延迟的时间，单位为tick
     */
    ITaskWrapper asyncLater(@NotNull Runnable task, long delayTicks);

    /**
     * 在延迟后循环执行任务
     *
     * @param task        需要执行的任务
     * @param delayTicks  延迟的时间，单位为tick
     * @param periodTicks 循环执行的间隔时间，单位为tick
     */
    ITaskWrapper asyncTimer(@NotNull Runnable task, long delayTicks, long periodTicks);

    void cancelTasks();

}
