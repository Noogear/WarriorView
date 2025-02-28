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
    private final double[] cosCache;
    private final double[] sinCache;
    private final boolean isRotation;
    private final int moveCount;
    private final long interval;

    public Up(AnimationParams params) {
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
            cosCache[step] = Math.cos(totalRad);
            sinCache[step] = Math.sin(totalRad);
        }

        this.isRotation = params.angle() != 0;
    }

    @Override
    public void play(int entityId, Vector3d location, Vector unitVec, List<Player> players, Consumer<Vector3d> onComplete) {
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
        private final double[] rotated = new double[2];
        private final double denominator;
        private double y;
        private byte count = 0;
        private double speed = baseSpeed;
        private double distance = max;


        public Updater(int entityId, Vector3d location, Vector unitVec, List<Player> players, Consumer<Vector3d> onComplete) {
            this.initialLocation = location;
            Vector finalVec = unitVec.clone().setY(0).normalize();
            this.x = finalVec.getX() * acceleration;
            this.y = location.getY();
            this.z = finalVec.getZ() * acceleration;
            this.players = players;
            this.onComplete = onComplete;
            this.teleportPacket = new WrapperPlayServerEntityTeleport(entityId, location, 0f, 0f, false);
            this.onRotation = (isRotation) && (x != 0 || z != 0);
            this.rotated[0] = 0;
            this.rotated[1] = 0;
            this.denominator = x * x + z * z;
        }

        @Override
        public void run() {
            if (max < 0 || distance > 0) {
                speed += acceleration;
                y += speed;
                if (onRotation) {
                    rotate(x, z, count);
                    teleportPacket.setPosition(initialLocation.add(rotated[0], y, rotated[1]));
                } else {
                    teleportPacket.setPosition(initialLocation.withY(y));
                }
                PacketUtil.sendPacketToPlayers(teleportPacket, players);
                distance -= Math.abs(speed);
            }

            count++;
            if (count >= moveCount) {
                onComplete.accept(initialLocation.add(rotated[0], y, rotated[1]));
                AnimationTask.getInstance().cancelTask(interval, this);
            }
        }

        public void rotate(double x0, double z0, byte step) {
            double cos = cosCache[step];
            double sin = sinCache[step];
            double rx = x0 * cos - z0 * sin;
            double rz = x0 * sin + z0 * cos;
            double projectionScale = (rx * x0 + rz * z0) / denominator;
            this.rotated[0] = rx - projectionScale * x0;
            this.rotated[1] = rz - projectionScale * z0;
        }
    }
}