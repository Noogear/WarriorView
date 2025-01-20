package cn.warriorView.Util.Scheduler;

import cn.warriorView.Main;
import cn.warriorView.Util.Scheduler.task.ITaskWrapper;
import org.bukkit.Location;
import org.bukkit.entity.Entity;

public abstract class XRunnable implements Runnable {

    protected ITaskWrapper taskWrapper;

    @Override
    public abstract void run();

    public ITaskWrapper sync(Main main) {
        checkTaskNotNull();
        return setTaskWrapper(main.scheduler().sync(this));
    }

    public ITaskWrapper syncLater(Main main, long delayTicks) {
        checkTaskNotNull();
        return setTaskWrapper(main.scheduler().syncLater(this, delayTicks));
    }

    public ITaskWrapper syncTimer(Main main, long delayTicks, long periodTicks) {
        checkTaskNotNull();
        return setTaskWrapper(main.scheduler().syncTimer(this, delayTicks, periodTicks));
    }

    public ITaskWrapper async(Main main) {
        checkTaskNotNull();
        return setTaskWrapper(main.scheduler().async(this));
    }

    public ITaskWrapper asyncLater(Main main, long delayTicks) {
        checkTaskNotNull();
        return setTaskWrapper(main.scheduler().asyncLater(this, delayTicks));
    }

    public ITaskWrapper asyncTimer(Main main, long delayTicks, long periodTicks) {
        checkTaskNotNull();
        return setTaskWrapper(main.scheduler().asyncTimer(this, delayTicks, periodTicks));
    }

    public ITaskWrapper runOnLocation(Main main, Location location) {
        checkTaskNotNull();
        return setTaskWrapper(main.scheduler().runOnLocation(location, this));
    }

    public ITaskWrapper runOnLocationLater(Main main, Location location, long delayTicks) {
        checkTaskNotNull();
        return setTaskWrapper(main.scheduler().runOnLocationLater(location, this, delayTicks));
    }

    public ITaskWrapper runOnLocationTimer(Main main, Location location, long delayTicks, long periodTicks) {
        checkTaskNotNull();
        return setTaskWrapper(main.scheduler().runOnLocationTimer(location, this, delayTicks, periodTicks));
    }

    public ITaskWrapper runOnEntity(Main main, Entity entity) {
        checkTaskNotNull();
        return setTaskWrapper(main.scheduler().runOnEntity(entity, this, this));
    }

    public ITaskWrapper runOnEntityLater(Main main, Entity entity, long delayTicks) {
        checkTaskNotNull();
        return setTaskWrapper(main.scheduler().runOnEntityLater(entity, this, this, delayTicks));
    }

    public ITaskWrapper runOnEntityTimer(Main main, Entity entity, long delayTicks, long periodTicks) {
        checkTaskNotNull();
        return setTaskWrapper(main.scheduler().runOnEntityTimer(entity, this, this, delayTicks, periodTicks));
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
