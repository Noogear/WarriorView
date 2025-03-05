package cn.warriorView.object.animation;

import cn.warriorView.util.scheduler.XRunnable;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

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

    public void scheduleTask(long interval, Runnable task) {
        TaskGroup group = taskGroups.computeIfAbsent(interval, k -> new TaskGroup(interval));
        group.addTask(task);
    }

    public void cancelTask(long interval, Runnable task) {
        TaskGroup group = taskGroups.get(interval);
        if (group != null) {
            group.removeTask(task);
        }
    }

    private static class TaskGroup {
        private final long interval;
        private final Set<Runnable> tasks = new CopyOnWriteArraySet<>();
        private XRunnable runnable;
        private boolean isActive = false;

        public TaskGroup(long interval) {
            this.interval = interval;
            start();
        }

        private void start() {
            runnable = new XRunnable() {
                @Override
                public void run() {
                    tasks.forEach(Runnable::run);
                }
            };
            runnable.async(interval, interval);
            isActive = true;
        }

        public void addTask(Runnable task) {
            tasks.add(task);
            if (!isActive) {
                start();
            }
        }

        public void removeTask(Runnable task) {
            tasks.remove(task);
            if (tasks.isEmpty()) {
                cancel();
            }
        }

        public void cancel() {
            if (runnable != null) {
                runnable.cancel();
            }
            tasks.clear();
            isActive = false;
        }
    }


}
