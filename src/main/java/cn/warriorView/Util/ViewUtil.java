package cn.warriorView.Util;

import cn.warriorView.Object.Animation.IAnimation;
import cn.warriorView.Object.Format.TextFormat;
import cn.warriorView.Object.Offset;
import cn.warriorView.Object.Scale;
import cn.warriorView.Util.Scheduler.XRunnable;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import me.tofaa.entitylib.meta.EntityMeta;
import me.tofaa.entitylib.meta.display.AbstractDisplayMeta;
import me.tofaa.entitylib.meta.display.TextDisplayMeta;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public class ViewUtil {

    public static WrapperPlayServerSpawnEntity spawnPack = new WrapperPlayServerSpawnEntity(
            0,
            Optional.of(UUID.randomUUID()),
            EntityTypes.TEXT_DISPLAY,
            null,
            0f, 0f, 0f, 0, Optional.empty()
    );

    public static void spawnDisplay(
            IAnimation animation,
            boolean isShadow,
            float viewRange,
            byte viewMarge,
            boolean isSeeThrough,
            TextFormat textFormat,
            byte textOpacity,
            int backGroundColor,
            Scale scale,
            Location location,
            Player player,
            double value,
            Offset offset
    ) {
        Set<Player> players = PacketUtil.getNearbyPlayer(location, viewMarge);
        players.add(player);
        new XRunnable() {
            @Override
            public void run() {
                int entityId = PacketUtil.getAutoEntityId();
                Vector3d finalLoc = offset.getPosition(location);
                packetHolo(entityId, finalLoc, players, value, isShadow, viewRange, isSeeThrough, textFormat, textOpacity, backGroundColor, scale);
                animation.play(entityId, finalLoc, location.getDirection().normalize(), players, null);
            }
        }.async();

    }

    public static void spawnDisplay(
            IAnimation animation,
            boolean isShadow,
            float viewRange,
            byte viewMarge,
            boolean isSeeThrough,
            TextFormat textFormat,
            byte textOpacity,
            int backGroundColor,
            Scale scale,
            LivingEntity entity,
            LivingEntity attacker,
            Player player,
            double value,
            Offset offset
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
                packetHolo(entityId, finalLoc, players, value, isShadow, viewRange, isSeeThrough, textFormat, textOpacity, backGroundColor, scale);
                animation.play(entityId, finalLoc, unitVec.multiply(-1), players, null);
            }
        }.async();

    }


    public static void packetHolo(
            int entityId,
            Vector3d location,
            Set<Player> players,
            double value,
            boolean isShadow,
            float viewRange,
            boolean isSeeThrough,
            TextFormat textFormat,
            byte textOpacity,
            int backGroundColor,
            Scale scale
    ) {
        TextDisplayMeta meta = (TextDisplayMeta) EntityMeta.createMeta(entityId, EntityTypes.TEXT_DISPLAY);
        meta.setBillboardConstraints(AbstractDisplayMeta.BillboardConstraints.CENTER);
        meta.setPositionRotationInterpolationDuration(10);
        meta.setTransformationInterpolationDuration(10);
        meta.setShadow(isShadow);
        meta.setViewRange(viewRange);
        meta.setSeeThrough(isSeeThrough);
        meta.setText(textFormat.get(value));
        meta.setTextOpacity(textOpacity);
        meta.setUseDefaultBackground(false);
        meta.setBackgroundColor(backGroundColor);
        meta.setScale(scale.getRandom());
        spawnPack.setEntityId(entityId);
        spawnPack.setPosition(location);
        PacketUtil.sendPacketToPlayers(spawnPack, players);
        PacketUtil.sendPacketToPlayers(meta.createPacket(), players);
    }

}
