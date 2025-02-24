package cn.warriorView.Object.Animation;

import cn.warriorView.Util.Scheduler.XRunnable;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class AnimationTask {
    private static AnimationTask instance;
    private final Map<Long, TaskGroup> taskGroups = new ConcurrentHashMap<>();

    public static AnimationTask getInstance() {
        if (instance == null) {
            instance = new AnimationTask();
        }
        return instance;
    }

    public void scheduleTask(long interval, Runnable task) {
        TaskGroup group = taskGroups.computeIfAbsent(interval, k -> new TaskGroup(interval));
        group.addTask(task);
    }

    public void cancelTask(long interval, Runnable task) {
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
        private final Set<Runnable> tasks = new HashSet<>();
        private XRunnable runnable;

        public TaskGroup(long interval) {
            this.interval = interval;
            start();
        }

        private void start() {
            runnable = new XRunnable() {
                @Override
                public void run() {
                    new HashSet<>(tasks).forEach(Runnable::run);
                }
            };
            runnable.asyncTimer(interval, interval);
        }

        public void addTask(Runnable task) {
            tasks.add(task);
        }

        public void removeTask(Runnable task) {
            tasks.remove(task);
        }

        public boolean isEmpty() {
            return tasks.isEmpty();
        }

        public void cancel() {
            runnable.cancel();
        }
    }


}
