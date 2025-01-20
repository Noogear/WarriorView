package cn.warriorView.Util;

import cn.warriorView.Util.Scheduler.XRunnable;
import cn.warriorView.View.DamageView.DamageOtherView;
import cn.warriorView.View.ViewDisplay;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public class PacketUtil {

    private static final PlayerManager playerManager = PacketEvents.getAPI().getPlayerManager();
    private static final AtomicInteger autoEntityId = new AtomicInteger(1000000);

    public static int getAutoEntityId() {
        return autoEntityId.incrementAndGet();
    }

    public static void sendPacketNearBy(PacketWrapper<?> packet, Location location, byte marge) {
        for (Player p : location.getNearbyPlayers(marge)) {
            playerManager.sendPacket(p, packet);
        }
    }

    public static void sendPacketListPlayer(PacketWrapper<?> packet, Set<Player> players) {
        for (Player p : players) {
            playerManager.sendPacket(p, packet);
        }
    }

    public static Vector3d locationToV3d(Location location) {
        return new Vector3d(location.getX(), location.getY(), location.getZ());
    }





}
