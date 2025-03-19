package cn.warriorView.object.animation;

import cn.warriorView.util.scheduler.XRunnable;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicLong;

public class AnimationTask {
    private static volatile AnimationTask instance;
    private final Map<Long, TaskGroup> taskGroups = new ConcurrentHashMap<>();

    public static AnimationTask getInstance() {
        if (instance == null) {
            synchronized (AnimationTask.class) {
                if (instance == null) {
                    instance = new AnimationTask();
                }
            }
        }
        return instance;
    }

    public void init() {
        taskGroups.values().forEach(TaskGroup::cancel);
        taskGroups.clear();
    }

    public void scheduleTask(long interval, XRunnable task) {
        if (task == null) return;
        taskGroups.computeIfAbsent(interval, k -> new TaskGroup(interval))
                .addTask(task);
    }

    public void cancelTask(long interval, XRunnable task) {
        TaskGroup group = taskGroups.get(interval);
        if (group != null) {
            group.removeTask(task);
            if (group.isEmpty()) {
                group.cancel();
                taskGroups.remove(interval);
            }
        }
    }

    private static class TaskGroup {
        private final long interval;
        private final long intervalMs;
        private final Set<XRunnable> tasks = new CopyOnWriteArraySet<>();
        private final Set<XRunnable> pendingTasks = new CopyOnWriteArraySet<>();
        private final AtomicLong nextExecutionTime = new AtomicLong();
        private XRunnable runnable;

        public TaskGroup(long interval) {
            this.interval = interval;
            this.intervalMs = interval * 50L;
            start();
        }

        private void start() {
            nextExecutionTime.set(System.currentTimeMillis() + intervalMs);
            runnable = new XRunnable() {
                @Override
                public void run() {
                    if (!pendingTasks.isEmpty()) {
                        tasks.addAll(pendingTasks);
                        pendingTasks.clear();
                    }
                    tasks.forEach(XRunnable::run);
                    nextExecutionTime.set(System.currentTimeMillis() + intervalMs);
                }
            };
            runnable.async(interval, interval);
        }


        public void addTask(XRunnable task) {
            long remainingMs = nextExecutionTime.get() - System.currentTimeMillis();
            if (remainingMs >= (intervalMs >> 1)) {
                tasks.add(task);
            } else {
                pendingTasks.add(task);
            }
        }

        public void removeTask(XRunnable task) {
            tasks.remove(task);
        }

        public boolean isEmpty() {
            return tasks.isEmpty();
        }

        public void cancel() {
            runnable.cancel();
            tasks.clear();
            pendingTasks.clear();
            nextExecutionTime.set(0);
        }
    }


}
