package cn.warriorView.Util.Scheduler;

import cn.warriorView.Util.Scheduler.task.ITaskWrapper;

public abstract class XRunnable implements Runnable {

    protected ITaskWrapper taskWrapper;

    @Override
    public abstract void run();

    public ITaskWrapper async() {
        checkTaskNotNull();
        return setTaskWrapper(XScheduler.get().async(this));
    }

    public ITaskWrapper asyncLater(long delayTicks) {
        checkTaskNotNull();
        return setTaskWrapper(XScheduler.get().asyncLater(this, delayTicks));
    }

    public ITaskWrapper asyncTimer(long delayTicks, long periodTicks) {
        checkTaskNotNull();
        return setTaskWrapper(XScheduler.get().asyncTimer(this, delayTicks, periodTicks));
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
