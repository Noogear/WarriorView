package cn.warriorView.Object.Animation.Type;

import cn.warriorView.Object.Animation.AnimationParams;
import cn.warriorView.Object.Animation.AnimationTask;
import cn.warriorView.Object.Animation.IAnimation;
import cn.warriorView.Util.PacketUtil;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.Set;
import java.util.function.Consumer;

public class Up implements IAnimation {
    private final float max;
    private final float baseSpeed;
    private final float acceleration;
    private final double cosAngle;
    private final double sinAngle;
    private final boolean rotate;
    private final int moveCount;
    private final long interval;

    public Up(AnimationParams params) {
        this.max = params.max();
        this.baseSpeed = params.baseSpeed();
        this.acceleration = (params.maxSpeed() - params.baseSpeed()) / params.moveCount();
        this.cosAngle = Math.cos(params.angle());
        this.sinAngle = Math.sin(params.angle());
        if(params.angle() == 0){
            this.rotate = false;
        } else {
            this.rotate = true;
        }
        this.moveCount = params.moveCount();
        this.interval = params.interval();
    }

    @Override
    public void play(int entityId, Vector3d location, Vector unitVec, Set<Player> players, Consumer<Vector3d> onComplete) {
        Updater updater = new Updater(entityId, location, players, onComplete);
        AnimationTask.getInstance().scheduleTask(interval, updater);
    }

    private class Updater implements Runnable {
        private final Vector3d initialLocation;
        private final WrapperPlayServerEntityTeleport teleportPacket;
        private final Set<Player> players;
        private final Consumer<Vector3d> onComplete;
        private int count = 0;
        private double speed = baseSpeed;
        private double y;

        public Updater(int entityId, Vector3d location, Set<Player> players, Consumer<Vector3d> onComplete) {
            this.initialLocation = location;
            this.y = location.getY() + speed;
            this.players = players;
            this.onComplete = onComplete;
            this.teleportPacket = new WrapperPlayServerEntityTeleport(entityId, location, 0f, 0f, false);
        }

        @Override
        public void run() {
            teleportPacket.setPosition(initialLocation.withY(y));
            PacketUtil.sendPacketToPlayers(teleportPacket, players);

            if (speed <= max) {
                speed += acceleration;
                y += speed;
            }

            count++;
            if (count >= moveCount) {
                onComplete.accept(initialLocation.withY(y));
                AnimationTask.getInstance().cancelTask(interval, this);
            }
        }
    }
}