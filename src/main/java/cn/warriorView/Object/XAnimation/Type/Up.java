package cn.warriorView.Object.XAnimation.Type;

import cn.warriorView.Object.XAnimation.AnimationParams;
import cn.warriorView.Object.XAnimation.IAnimation;
import cn.warriorView.Util.PacketUtil;
import cn.warriorView.Util.Scheduler.XRunnable;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.Set;
import java.util.function.Consumer;

public class Up implements IAnimation {

    float max;
    float baseSpeed;
    float acceleration;
    double CosAngle;
    double SinAngle;
    int moveCount;
    long interval;

    public Up(AnimationParams params) {
        this.max = params.max();
        this.baseSpeed = params.baseSpeed();
        this.acceleration = params.maxSpeed() / params.moveCount();
        this.CosAngle = Math.cos(params.angle());
        this.SinAngle = Math.sin(params.angle());
        this.moveCount = params.moveCount();
        this.interval = params.interval();
    }

    @Override
    public void play(int entityId, Vector3d location, Vector unitVec, Set<Player> players, Consumer<Vector3d> onComplete) {
        new XRunnable() {
            final double y = location.getY();
            byte count = 0;
            double speed = baseSpeed;

            @Override
            public void run() {
                if (count >= moveCount) {
                    onComplete.accept(location.withY(y + count * speed));
                    cancel();
                    return;
                }

                count++;
                PacketUtil.sendPacketToPlayers(new WrapperPlayServerEntityTeleport(
                        entityId,
                        location.withY(y + count * speed),
                        0f,
                        0f,
                        false
                ), players);
                if (speed <= max) {
                    speed += acceleration;
                }
            }
        }.asyncTimer(interval, interval);
    }
}
