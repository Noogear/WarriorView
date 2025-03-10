package cn.warriorView.object.animation;

import cn.warriorView.util.PacketUtil;
import cn.warriorView.util.scheduler.XRunnable;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.Set;
import java.util.function.Consumer;

public abstract class BaseAnimation implements IAnimation {
    protected final float max;
    protected final float baseSpeed;
    protected final float acceleration;
    protected final double cosCache;
    protected final double sinCache;
    protected final boolean isRotation;
    protected final int moveCount;
    protected final long interval;
    protected final ScheduleStrategy scheduleStrategy;

    protected BaseAnimation(AnimationParams params) {
        this.max = params.max();
        this.baseSpeed = params.baseSpeed();
        this.acceleration = params.acceleration();
        this.moveCount = params.moveCount();
        this.interval = params.interval();

        double theta = Math.toRadians(params.angle());
        this.cosCache = Math.cos(theta);
        this.sinCache = Math.sin(theta);
        this.isRotation = params.angle() != 0;
        this.scheduleStrategy = ScheduleStrategy.fromMoveCount(params.moveCount());
    }

    @Override
    public void play(int entityId, Vector3d location, Vector unitVec, Set<Player> players, Consumer<Vector3d> onComplete) {
        if (players.isEmpty()) return;
        BaseUpdater updater = createUpdater(entityId, location, processDirection(unitVec), players, onComplete);
        scheduleStrategy.schedule(updater, interval);
    }

    protected abstract Vector processDirection(Vector original);

    protected abstract BaseUpdater createUpdater(int entityId, Vector3d location, Vector direction, Set<Player> players, Consumer<Vector3d> onComplete);

    public enum ScheduleStrategy {
        SCHEDULED {
            @Override
            public void schedule(Runnable task, long interval) {
                AnimationTask.getInstance().scheduleTask(interval, task);
            }

            @Override
            public void cancel(Runnable task, long interval) {
                AnimationTask.getInstance().cancelTask(interval, task);
            }
        },
        ASYNC {
            @Override
            public void schedule(Runnable task, long interval) {
                ((XRunnable) task).async(interval);
            }

            @Override
            public void cancel(Runnable task, long interval) {
                ((XRunnable) task).cancel();
            }
        };

        public static ScheduleStrategy fromMoveCount(int moveCount) {
            return moveCount == 1 ? ASYNC : SCHEDULED;
        }

        public abstract void schedule(Runnable task, long interval);

        public abstract void cancel(Runnable task, long interval);
    }

    protected abstract class BaseUpdater extends XRunnable {
        protected final Vector3d initialLocation;
        protected final WrapperPlayServerEntityTeleport teleportPacket;
        protected final Set<Player> players;
        protected final Consumer<Vector3d> onComplete;
        protected double speed = baseSpeed;
        protected double distance = 0;
        protected byte count = 0;
        protected double move = 0;

        public BaseUpdater(int entityId, Vector3d location, Set<Player> players, Consumer<Vector3d> onComplete) {
            this.initialLocation = location;
            this.players = players;
            this.onComplete = onComplete;
            this.teleportPacket = new WrapperPlayServerEntityTeleport(entityId, null, 0f, 0f, false);
        }

        @Override
        public void run() {
            if (max < 0 || distance < max) {
                move += speed;
                distance += Math.abs(speed);
                speed += acceleration;
                updatePosition();
                if (!PacketUtil.sendPacketToPlayers(teleportPacket, players)) {
                    scheduleStrategy.cancel(this, interval);
                    return;
                }
            }
            if (++count >= moveCount) {
                scheduleStrategy.cancel(this, interval);
                onComplete.accept(teleportPacket.getPosition());
            }
        }

        protected abstract void updatePosition();
    }
}
