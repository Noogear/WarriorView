package cn.warriorView.View.Animation;

import cn.warriorView.Main;
import cn.warriorView.Util.PacketUtil;
import cn.warriorView.Util.Scheduler.XRunnable;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import org.bukkit.entity.Player;

import java.util.Set;

public class UpAndDown extends Animation {

    public UpAndDown(Main main, byte moveCount, float max, float speed) {
        super(main, moveCount, max, speed);
    }

    @Override
    public void play(int entityId, Vector3d location, Set<Player> players) {

        new XRunnable() {
            byte count = 0;
            double changeY = max;

            @Override
            public void run() {

                if (count >= moveCount) {
                    PacketUtil.sendPacketListPlayer(new WrapperPlayServerDestroyEntities(entityId), players);
                    players.clear();
                    cancel();
                }

                Vector3d tpLocation = location.withY((count + 1) * changeY);
                PacketUtil.sendPacketListPlayer(new WrapperPlayServerEntityTeleport(
                        entityId,
                        tpLocation,
                        0f,
                        0f,
                        false
                ), players);

                count++;
                changeY -= speed;

            }
        }.asyncTimer(plugin, 2, 2);
    }

}
