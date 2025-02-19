package cn.warriorView.Object.Animation.Type;

import cn.warriorView.Object.Animation.AnimationParams;
import cn.warriorView.Object.Animation.IAnimation;
import cn.warriorView.Util.PacketUtil;
import cn.warriorView.Util.Scheduler.XRunnable;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Set;

public class Approach implements IAnimation {
    final float max;
    final float initial;
    final float acceleration;
    final float offset;
    final byte moveCount;
    final long delay;

    public Approach(AnimationParams params, byte moveCount, long delay) {
        this.max = params.max();
        this.initial = params.initial();
        this.acceleration = params.acceleration();
        this.offset = params.offset();
        this.moveCount = moveCount;
        this.delay = delay;
    }

    @Override
    public Vector3d offset(Location location) {
        return new Vector3d(location.getX(), location.getY() + offset, location.getZ());
    }

    @Override
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
}
