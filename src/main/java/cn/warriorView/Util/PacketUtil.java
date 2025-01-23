package cn.warriorView.Util;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public class PacketUtil {

    private static final PlayerManager playerManager = PacketEvents.getAPI().getPlayerManager();
    private static final AtomicInteger autoEntityId = new AtomicInteger(1000000);

    public static int getAutoEntityId() {
        return autoEntityId.incrementAndGet();
    }

    public static void sendPacketSetPlayer(PacketWrapper<?> packet, Set<Player> players) {
        for (Player p : players) {
            if (p == null) continue;
            playerManager.sendPacket(p, packet);
        }
    }

    public static Vector3d locationToV3d(Location location) {
        return new Vector3d(location.getX(), location.getY(), location.getZ());
    }

    public static Set<Player> getNearbyPlayer(Location location, byte marge) {
        if (marge <= 1) {
            return Collections.emptySet();
        }
        return new HashSet<>(location.getNearbyPlayers(marge));
    }

}
