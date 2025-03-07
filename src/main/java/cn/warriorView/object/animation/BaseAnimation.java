package cn.warriorView.object.animation;

import cn.warriorView.util.PacketUtil;
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

    protected BaseAnimation(AnimationParams params) {
        this.max = params.max();
        this.baseSpeed = params.baseSpeed();
        this.acceleration = (params.maxSpeed() - params.baseSpeed()) / params.moveCount();
        this.moveCount = params.moveCount();
        this.interval = params.interval();

        double theta = Math.toRadians(params.angle());
        this.cosCache = Math.cos(theta);
        this.sinCache = Math.sin(theta);
        this.isRotation = params.angle() != 0;
    }

    @Override
    public void play(int entityId, Vector3d location, Vector unitVec, Set<Player> players, Consumer<Vector3d> onComplete) {
        if (players.isEmpty()) return;
        AnimationTask.getInstance().scheduleTask(interval, createUpdater(entityId, location, processDirection(unitVec), players, onComplete));
    }

    protected abstract Vector processDirection(Vector original);

    protected abstract BaseUpdater createUpdater(int entityId, Vector3d location, Vector direction, Set<Player> players, Consumer<Vector3d> onComplete);

    protected abstract class BaseUpdater implements Runnable {
        protected final Vector3d initialLocation;
        protected final WrapperPlayServerEntityTeleport teleportPacket;
        protected final Set<Player> players;
        protected final Consumer<Vector3d> onComplete;
        protected double speed = baseSpeed;
        protected double distance = max;
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
            if (max < 0 || distance > 0) {
                speed += acceleration;
                move += speed;
                distance -= Math.abs(speed);
                updatePosition();
                if (!PacketUtil.sendPacketToPlayers(teleportPacket, players)) {
                    AnimationTask.getInstance().cancelTask(interval, this);
                    return;
                }
            }
            if (++count >= moveCount) {
                AnimationTask.getInstance().cancelTask(interval, this);
                onComplete.accept(teleportPacket.getPosition());
            }
        }

        protected abstract void updatePosition();
    }
}
