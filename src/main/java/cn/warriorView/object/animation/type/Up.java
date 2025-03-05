package cn.warriorView.object.animation.type;

import cn.warriorView.object.animation.AnimationParams;
import cn.warriorView.object.animation.AnimationTask;
import cn.warriorView.object.animation.IAnimation;
import cn.warriorView.util.PacketUtil;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.function.Consumer;

public class Up implements IAnimation {
    private final float max;
    private final float baseSpeed;
    private final float acceleration;
    private final double cosCache;
    private final double sinCache;
    private final boolean isRotation;
    private final int moveCount;
    private final long interval;

    public Up(AnimationParams params) {
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
    public void play(int entityId, Vector3d location, Vector unitVec, List<Player> players, Consumer<Vector3d> onComplete) {
        if (players.isEmpty()) return;
        Updater updater = new Updater(entityId, location, unitVec, players, onComplete);
        AnimationTask.getInstance().scheduleTask(interval, updater);
    }

    private class Updater implements Runnable {
        private final Vector3d initialLocation;
        private final WrapperPlayServerEntityTeleport teleportPacket;
        private final List<Player> players;
        private final Consumer<Vector3d> onComplete;
        private final double[] rotated = new double[3];
        private double move;
        private byte count = 0;
        private double speed = baseSpeed;
        private double distance = max;


        public Updater(int entityId, Vector3d location, Vector direction, List<Player> players, Consumer<Vector3d> onComplete) {
            this.initialLocation = location;
            if (isRotation) {
                this.move = 0;
            } else {
                this.move = location.getY();
            }
            this.players = players;
            this.onComplete = onComplete;
            this.teleportPacket = new WrapperPlayServerEntityTeleport(entityId, null, 0f, 0f, false);
            Vector finalVec = direction.clone().setY(0).normalize();
            rotate(finalVec.getX(), finalVec.getZ());
        }

        @Override
        public void run() {
            if (max < 0 || distance > 0) {
                speed += acceleration;
                move += speed;
                if (isRotation) {
                    teleportPacket.setPosition(initialLocation.add(rotated[0] * move, rotated[1] * move, rotated[2] * move));
                } else {
                    teleportPacket.setPosition(initialLocation.withY(move));
                }
                if (!PacketUtil.sendPacketToPlayers(teleportPacket, players)) {
                    AnimationTask.getInstance().cancelTask(interval, this);
                    return;
                }
                distance -= Math.abs(speed);
            }
            count++;
            if (count >= moveCount) {
                AnimationTask.getInstance().cancelTask(interval, this);
                onComplete.accept(teleportPacket.getPosition());
            }
        }

        public void rotate(double x, double z) {
            rotated[0] = z * sinCache;
            rotated[1] = cosCache;
            rotated[2] = -x * sinCache;
        }
    }
}