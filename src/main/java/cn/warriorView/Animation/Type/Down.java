package cn.warriorView.Animation.Type;

import cn.warriorView.Animation.Animation;
import cn.warriorView.Animation.AnimationParams;
import cn.warriorView.Util.PacketUtil;
import cn.warriorView.Util.Scheduler.XRunnable;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import org.bukkit.entity.Player;

import java.util.Set;

public class Down extends Animation {


    public Down(AnimationParams params, byte moveCount, long delay) {
        super(params, moveCount, delay);
    }

    @Override
    public void play(int entityId, Vector3d location, Set<Player> players) {
        new XRunnable() {
            byte count = 0;
            double changeY = 0;

            @Override
            public void run() {

                if (count >= moveCount() || changeY > max()) {
                    PacketUtil.sendPacketSetPlayer(new WrapperPlayServerDestroyEntities(entityId), players);
                    players.clear();
                    cancel();
                    return;
                }

                count++;
                Vector3d tpLocation = location.add(0, -count * changeY, 0);
                PacketUtil.sendPacketSetPlayer(new WrapperPlayServerEntityTeleport(
                        entityId,
                        tpLocation,
                        0f,
                        0f,
                        false
                ), players);
                changeY += speed();

            }
        }.asyncTimer(delay(), delay());
    }
}
