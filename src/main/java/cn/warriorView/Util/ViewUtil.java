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

    private static final ThreadLocal<WrapperPlayServerSpawnEntity> threadLocalSpawnPack =
            ThreadLocal.withInitial(() -> new WrapperPlayServerSpawnEntity(
                    0,
                    Optional.of(UUID.randomUUID()),
                    EntityTypes.TEXT_DISPLAY,
                    null,
                    0f, 0f, 0f, 0, Optional.empty()
            ));

    private static final ThreadLocal<WrapperPlayServerEntityMetadata> threadLocalMetaPack =
            ThreadLocal.withInitial(() -> new WrapperPlayServerEntityMetadata(
                    0,
                    (List<EntityData>) null
            ));

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
        WrapperPlayServerEntityMetadata metaPack = threadLocalMetaPack.get();
        basicSpawnData.add(TextDisplayMeta.scale(scale.get()));
        basicSpawnData.add(TextDisplayMeta.text(textFormat.get(value)));
        metaPack.setEntityId(entityId);
        metaPack.setEntityMetadata(basicSpawnData);

        WrapperPlayServerSpawnEntity spawnPack = threadLocalSpawnPack.get();
        spawnPack.setEntityId(entityId);
        spawnPack.setPosition(location);

        PacketUtil.sendPacketToPlayers(spawnPack, players);
        PacketUtil.sendPacketToPlayers(metaPack, players);
    }

}
