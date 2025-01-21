package cn.warriorView.Util.Scheduler;

import cn.warriorView.Util.Scheduler.task.ITaskWrapper;
import org.bukkit.Location;
import org.bukkit.entity.Entity;

public abstract class XRunnable implements Runnable {

    protected ITaskWrapper taskWrapper;

    @Override
    public abstract void run();

    public ITaskWrapper sync() {
        checkTaskNotNull();
        return setTaskWrapper(XScheduler.get().sync(this));
    }

    public ITaskWrapper syncLater(long delayTicks) {
        checkTaskNotNull();
        return setTaskWrapper(XScheduler.get().syncLater(this, delayTicks));
    }

    public ITaskWrapper syncTimer(long delayTicks, long periodTicks) {
        checkTaskNotNull();
        return setTaskWrapper(XScheduler.get().syncTimer(this, delayTicks, periodTicks));
    }

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

    public ITaskWrapper runOnLocation(Location location) {
        checkTaskNotNull();
        return setTaskWrapper(XScheduler.get().runOnLocation(location, this));
    }

    public ITaskWrapper runOnLocationLater(Location location, long delayTicks) {
        checkTaskNotNull();
        return setTaskWrapper(XScheduler.get().runOnLocationLater(location, this, delayTicks));
    }

    public ITaskWrapper runOnLocationTimer(Location location, long delayTicks, long periodTicks) {
        checkTaskNotNull();
        return setTaskWrapper(XScheduler.get().runOnLocationTimer(location, this, delayTicks, periodTicks));
    }

    public ITaskWrapper runOnEntity(Entity entity) {
        checkTaskNotNull();
        return setTaskWrapper(XScheduler.get().runOnEntity(entity, this, this));
    }

    public ITaskWrapper runOnEntityLater(Entity entity, long delayTicks) {
        checkTaskNotNull();
        return setTaskWrapper(XScheduler.get().runOnEntityLater(entity, this, this, delayTicks));
    }

    public ITaskWrapper runOnEntityTimer(Entity entity, long delayTicks, long periodTicks) {
        checkTaskNotNull();
        return setTaskWrapper(XScheduler.get().runOnEntityTimer(entity, this, this, delayTicks, periodTicks));
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
