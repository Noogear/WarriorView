package cn.warriorView.Object.Animation;

import cn.warriorView.Object.Offset;
import cn.warriorView.Util.PacketUtil;
import cn.warriorView.Util.Scheduler.XRunnable;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import org.bukkit.entity.Player;

import java.util.Set;

public class Down extends Animation {

    public Down(byte moveCount, float max, float speed, long delay, Offset offset) {
        super(moveCount, max, speed, delay, offset);
    }

    @Override
    public void play(int entityId, Vector3d location, Set<Player> players) {
        new XRunnable() {
            byte count = 0;
            double changeY = 0;

            @Override
            public void run() {

                if (count >= moveCount || changeY > max) {
                    PacketUtil.sendPacketSetPlayer(new WrapperPlayServerDestroyEntities(entityId), players);
                    players.clear();
                    cancel();
                    return;
                }

                Vector3d tpLocation = location.withY(-(count + 1) * changeY);
                PacketUtil.sendPacketSetPlayer(new WrapperPlayServerEntityTeleport(
                        entityId,
                        tpLocation,
                        0f,
                        0f,
                        false
                ), players);

                count++;
                changeY += speed;

            }
        }.asyncTimer(delay, delay);
    }
}
