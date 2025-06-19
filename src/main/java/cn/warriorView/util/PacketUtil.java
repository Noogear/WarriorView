package cn.warriorView.util;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.manager.server.VersionComparison;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Set;

public class PacketUtil {

    private static final PlayerManager playerManager = PacketEvents.getAPI().getPlayerManager();

    public static boolean sendPacketToPlayers(PacketWrapper<?> packet, Set<Player> players) {
        if (players.isEmpty()) {
            return false;
        } else {
            players.removeIf(p -> {
                if (p == null || !p.isOnline()) {
                    return true;
                } else {
                    playerManager.sendPacket(p, packet);
                    return false;
                }
            });
            return true;
        }
    }

    public static void sendPacketToPlayers(PacketWrapper<?> packet1, PacketWrapper<?> packet2, Set<Player> players) {
        players.removeIf(p -> {
            if (p == null) {
                return true;
            } else {
                playerManager.sendPacket(p, packet1);
                playerManager.sendPacket(p, packet2);
                return false;
            }
        });
    }

    public static HashSet<Player> getNearbyPlayer(Location location, byte marge) {
        return new HashSet<>(location.getWorld().getNearbyEntitiesByType(Player.class, location, marge, marge, marge, null));
    }

    public static boolean isVersion(ServerVersion targetVersion, VersionComparison comparison) {
        return PacketEvents.getAPI().getServerManager().getVersion().is(comparison, targetVersion);
    }

}
