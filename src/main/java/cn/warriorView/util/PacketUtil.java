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

public class PacketUtil {

    private static final PlayerManager playerManager = PacketEvents.getAPI().getPlayerManager();

    public static boolean sendPacketToPlayers(PacketWrapper<?> packet, List<Player> players) {
        if (players.isEmpty()) {
            return false;
        } else {
            players.removeIf(p -> {
                if (p == null) {
                    return true;
                } else {
                    playerManager.sendPacket(p, packet);
                    return false;
                }
            });
            return true;
        }
    }

    public static void sendPacketToPlayers(PacketWrapper<?> packet1, PacketWrapper<?> packet2, List<Player> players) {
        for (Player p : players) {
            if (p == null) {
                continue;
            }
            playerManager.sendPacket(p, packet1);
            playerManager.sendPacket(p, packet2);
        }
    }

    public static List<Player> getNearbyPlayer(Location location, byte marge) {
        if (marge <= 1) {
            return Collections.emptyList();
        }
        return new ArrayList<>(location.getWorld().getNearbyEntitiesByType(Player.class, location, marge, marge, marge, null));
    }

    public static boolean isVersion(ServerVersion targetVersion, VersionComparison comparison) {
        return PacketEvents.getAPI().getServerManager().getVersion().is(comparison, targetVersion);
    }

}
