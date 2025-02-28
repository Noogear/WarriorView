package cn.warriorView.util;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.manager.server.VersionComparison;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class PacketUtil {

    private static final PlayerManager playerManager = PacketEvents.getAPI().getPlayerManager();
    private static final AtomicInteger autoEntityId = new AtomicInteger(1000000);

    public static int getAutoEntityId() {
        return autoEntityId.incrementAndGet();
    }

    public static void sendPacketToPlayers(PacketWrapper<?> packet, List<Player> players) {
        players.removeIf(player -> {
            if (player == null) {
                return true;
            } else {
                playerManager.sendPacket(player, packet);
                return false;
            }
        });
    }

    public static void sendPacketToPlayers(PacketWrapper<?> packet1, PacketWrapper<?> packet2, List<Player> players) {
        for (Player p : players) {
            playerManager.sendPacket(p, packet1);
            playerManager.sendPacket(p, packet2);
        }
    }

    public static List<Player> getNearbyPlayer(Location location, byte marge) {
        if (marge <= 1) {
            return Collections.emptyList();
        }
        return new ArrayList<>(location.getNearbyPlayers(marge));
    }

    public static int getProtocolVersion() {
        return PacketEvents.getAPI().getServerManager().getVersion().getProtocolVersion();
    }

    public static boolean isVersion(ServerVersion targetVersion, VersionComparison comparison) {
        return PacketEvents.getAPI().getServerManager().getVersion().is(comparison, targetVersion);
    }

}
