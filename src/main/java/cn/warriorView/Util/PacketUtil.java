package cn.warriorView.util;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.manager.server.VersionComparison;
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

    public static void sendPacketToPlayers(PacketWrapper<?> packet, Set<Player> players) {
        for (Player p : players) {
            if (p == null) continue;
            playerManager.sendPacket(p, packet);
        }
    }

    public static Set<Player> getNearbyPlayer(Location location, byte marge) {
        if (marge <= 1) {
            return Collections.emptySet();
        }
        return new HashSet<>(location.getNearbyPlayers(marge));
    }

    public static int getProtocolVersion() {
        return PacketEvents.getAPI().getServerManager().getVersion().getProtocolVersion();
    }

    public static boolean isVersion(ServerVersion targetVersion, VersionComparison comparison) {
        return PacketEvents.getAPI().getServerManager().getVersion().is(comparison, targetVersion);
    }

}
