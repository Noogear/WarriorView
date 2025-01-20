package cn.warriorView.Util;

import cn.warriorView.View.DamageView.DamageOtherView;
import cn.warriorView.View.ViewDisplay;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
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

    public static void spawnDisplay(ViewDisplay viewDisplay, Location location, Set<Player> players) {

    }

    public static void spawnDisplay(DamageOtherView viewDisplay, Location entityLocation, Location attackerLocation, Set<Player> players) {

    }





}
