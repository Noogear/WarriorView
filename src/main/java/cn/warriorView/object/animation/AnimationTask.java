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

    public void scheduleTask(long interval, XRunnable task) {
        TaskGroup group = taskGroups.computeIfAbsent(interval, k -> new TaskGroup(interval));
        group.addTask(task);
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
        private final Set<XRunnable> tasks = new CopyOnWriteArraySet<>();
        private XRunnable runnable;

        public TaskGroup(long interval) {
            this.interval = interval;
            start();
        }

        private void start() {
            runnable = new XRunnable() {
                @Override
                public void run() {
                    tasks.forEach(XRunnable::run);
                }
            };
            runnable.async(interval, interval);
        }

        public void addTask(XRunnable task) {
            tasks.add(task);
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
        }
    }


}