package cn.warriorView.Object.Animation;

import cn.warriorView.Util.PacketUtil;
import cn.warriorView.Util.Scheduler.XRunnable;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.Set;

public class Animation {
    private final float max;
    private final float initial;
    private final float acceleration;
    private final Vector offset;
    private final byte moveCount;
    private final long delay;

    protected Animation(AnimationParams params, byte moveCount, long delay) {
        this.max = params.max();
        this.initial = params.initial();
        this.acceleration = params.acceleration();
        this.offset = params.offset();
        this.moveCount = moveCount;
        this.delay = delay;
    }

    public static Animation create(AnimationParams params, byte moveCount, long delay) {
        return new Animation(params, moveCount, delay);
    }

    public void play(int entityId, Vector3d location, Set<Player> players) {
        new XRunnable() {
            final double y = location.getY();
            byte count = 0;
            double speed = initial;

            @Override
            public void run() {
                if (count >= moveCount) {
                    PacketUtil.sendPacketToPlayers(new WrapperPlayServerDestroyEntities(entityId), players);
                    players.clear();
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
        }.asyncTimer(delay, delay);
    }

    public Vector offset() {
        return offset;
    }

}
