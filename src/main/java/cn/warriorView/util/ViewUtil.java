package cn.warriorView.util;

import cn.warriorView.object.Offset;
import cn.warriorView.object.animation.IAnimation;
import cn.warriorView.object.format.TextFormat;
import cn.warriorView.object.scale.IScale;
import cn.warriorView.util.scheduler.XRunnable;
import cn.warriorView.view.meta.TextDisplayMeta;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public class ViewUtil {

    private static final UUID uuid = UUID.randomUUID();

    public static void spawnDisplay(
            IAnimation animation,
            byte viewMarge,
            TextFormat textFormat,
            IScale scale,
            Location location,
            Player player,
            double value,
            Offset offset,
            List<EntityData> basicSpawnData
    ) {
        Set<Player> players = PacketUtil.getNearbyPlayer(location, viewMarge);
        players.add(player);
        new XRunnable() {
            @Override
            public void run() {
                int entityId = PacketUtil.getAutoEntityId();
                Vector3d finalLoc = offset.getPosition(location);
                packetHolo(entityId, finalLoc, players, value, textFormat, scale, basicSpawnData);
                animation.play(entityId, finalLoc, location.getDirection(), players, null);
            }
        }.async();
    }

    public static void spawnDisplay(
            IAnimation animation,
            byte viewMarge,
            TextFormat textFormat,
            IScale scale,
            LivingEntity entity,
            LivingEntity attacker,
            Player player,
            double value,
            Offset offset,
            List<EntityData> basicSpawnData
    ) {
        Set<Player> players = PacketUtil.getNearbyPlayer(entity.getEyeLocation(), viewMarge);
        players.add(player);
        new XRunnable() {
            @Override
            public void run() {
                Location entityLocation = entity.getEyeLocation();
                Location attackerLocation = attacker.getEyeLocation();
                Vector unitVec = attackerLocation.getDirection().normalize();
                Vector3d finalLoc = offset.getPosition(attackerLocation.add(unitVec.multiply(attackerLocation.distance(entityLocation))), unitVec);
                int entityId = PacketUtil.getAutoEntityId();
                packetHolo(entityId, finalLoc, players, value, textFormat, scale, basicSpawnData);
                animation.play(entityId, finalLoc, unitVec.multiply(-1), players, null);
            }
        }.async();
    }

    public static void packetHolo(
            int entityId,
            Vector3d location,
            Set<Player> players,
            double value,
            TextFormat textFormat,
            IScale scale,
            List<EntityData> basicSpawnData
    ) {
        basicSpawnData.add(TextDisplayMeta.scale(scale.get()));
        basicSpawnData.add(TextDisplayMeta.text(textFormat.get(value)));
        WrapperPlayServerEntityMetadata metaPack = new WrapperPlayServerEntityMetadata(entityId, basicSpawnData);
        WrapperPlayServerSpawnEntity spawnPack = new WrapperPlayServerSpawnEntity(
                entityId, Optional.of(uuid), EntityTypes.TEXT_DISPLAY,
                location, 0f, 0f, 0f, 0, Optional.empty());

        PacketUtil.sendPacketToPlayers(spawnPack, metaPack, players);

    }

}
