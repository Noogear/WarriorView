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

public class Approach implements IAnimation {
    private final float max;
    private final float baseSpeed;
    private final float acceleration;
    private final double[] cosCache;
    private final double[] sinCache;
    private final boolean isRotation;
    private final int moveCount;
    private final long interval;

    public Approach(AnimationParams params) {
        this.max = params.max();
        this.baseSpeed = params.baseSpeed();
        this.acceleration = (params.maxSpeed() - params.baseSpeed()) / params.moveCount();
        this.moveCount = params.moveCount();
        this.interval = params.interval();

        double radianStep = Math.toRadians(params.angle());
        this.cosCache = new double[moveCount];
        this.sinCache = new double[moveCount];
        for (int step = 0; step < moveCount; step++) {
            double totalRad = radianStep * step;
            cosCache[step] = Math.cos(totalRad) * acceleration;
            sinCache[step] = Math.sin(totalRad) * acceleration;
        }

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
        private final boolean onRotation;
        private final double x;
        private final double z;
        private final double y;
        private final double[] rotated = new double[2];
        private double move = 0;
        private byte count = 0;
        private double speed = baseSpeed;
        private double distance = max;

        public Updater(int entityId, Vector3d location, Vector unitVec, List<Player> players, Consumer<Vector3d> onComplete) {
            this.initialLocation = location;
            Vector finalVec = unitVec.clone().normalize();
            this.x = finalVec.getX();
            this.y = finalVec.getY();
            this.z = finalVec.getZ();
            this.players = players;
            this.onComplete = onComplete;
            this.teleportPacket = new WrapperPlayServerEntityTeleport(entityId, location, 0f, 0f, false);
            this.onRotation = (isRotation) && (x != 0 || z != 0);
            this.rotated[0] = 0;
            this.rotated[1] = 0;
        }

        @Override
        public void run() {
            if (max < 0 || distance > 0) {
                speed += acceleration;
                move += speed;
                if (onRotation) {
                    rotate(x, z, count);
                    teleportPacket.setPosition(initialLocation.add(x * move + rotated[0], y * move, z * move + rotated[1]));
                } else {
                    teleportPacket.setPosition(initialLocation.add(x * move, y * move, z * move));
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

        public void rotate(double x0, double z0, byte step) {
            double cos = cosCache[step];
            double sin = sinCache[step];
            this.rotated[0] = x0 * cos + z0 * sin;
            this.rotated[1] = z0 * cos - x0 * sin;
        }
    }


}
