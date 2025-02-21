package cn.warriorView.Object.Animation.Type;

import cn.warriorView.Object.Animation.AnimationParams;
import cn.warriorView.Object.Animation.IAnimation;
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
            byte count = 0;
            double speed = baseSpeed;
            double y = location.getY() + speed;

            @Override
            public void run() {
                if (count >= moveCount) {
                    onComplete.accept(location.withY(y));
                    cancel();
                    return;
                }
                PacketUtil.sendPacketToPlayers(new WrapperPlayServerEntityTeleport(
                        entityId,
                        location.withY(y),
                        0f,
                        0f,
                        false
                ), players);

                count++;

                if (speed <= max) {
                    speed += acceleration;
                    y += speed;
                }

            }
        }.asyncTimer(interval, interval);
    }
}
