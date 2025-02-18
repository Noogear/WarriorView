package cn.warriorView.Util;

import cn.warriorView.Object.Animation.Animation;
import cn.warriorView.Object.Replacement;
import cn.warriorView.Object.Scale;
import cn.warriorView.Util.Scheduler.XRunnable;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import me.tofaa.entitylib.meta.EntityMeta;
import me.tofaa.entitylib.meta.display.AbstractDisplayMeta;
import me.tofaa.entitylib.meta.display.TextDisplayMeta;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public class ViewUtil {

    public static void spawnDisplay(
            Animation animation,
            boolean isShadow,
            float viewRange,
            byte viewMarge,
            boolean isSeeThrough,
            String textFormat,
            Replacement replacement,
            byte textOpacity,
            int backGroundColor,
            Scale scale,
            Location location,
            Player player,
            double value
    ) {
        Set<Player> players = PacketUtil.getNearbyPlayer(location, viewMarge);
        players.add(player);
        new XRunnable() {

            @Override
            public void run() {
                int entityId = PacketUtil.getAutoEntityId();
                packetHolo(entityId, location, players, value, animation, isShadow, viewRange, isSeeThrough, textFormat, replacement, textOpacity, backGroundColor, scale);
                animation.play(entityId, PacketUtil.locationToV3d(location), players);
            }
        }.async();

    }

    public static void spawnDisplay(
            Animation animation,
            boolean isShadow,
            float viewRange,
            byte viewMarge,
            boolean isSeeThrough,
            String textFormat,
            Replacement replacement,
            byte textOpacity,
            int backGroundColor,
            Scale scale,
            LivingEntity entity,
            LivingEntity attacker,
            Player player,
            double value) {
        Set<Player> players = PacketUtil.getNearbyPlayer(entity.getEyeLocation(), viewMarge);
        players.add(player);
        new XRunnable() {

            @Override
            public void run() {
                Location entityLocation = entity.getEyeLocation();
                Location attackerLocation = attacker.getEyeLocation();
                Location damageLocation = attackerLocation.add(attackerLocation.getDirection().normalize().multiply(attackerLocation.distance(entityLocation)));
                int entityId = PacketUtil.getAutoEntityId();
                packetHolo(entityId, damageLocation, players, value, animation, isShadow, viewRange, isSeeThrough, textFormat, replacement, textOpacity, backGroundColor, scale);
                animation.play(entityId, PacketUtil.locationToV3d(damageLocation), players);
            }
        }.async();

    }


    public static void packetHolo(
            int entityId,
            Location location,
            Set<Player> players,
            double value,
            Animation animation,
            boolean isShadow,
            float viewRange,
            boolean isSeeThrough,
            String textFormat,
            Replacement replacement,
            byte textOpacity,
            int backGroundColor,
            Scale scale
    ) {
        location = location.add(animation.offset());
        TextDisplayMeta meta = (TextDisplayMeta) EntityMeta.createMeta(entityId, EntityTypes.TEXT_DISPLAY);
        meta.setBillboardConstraints(AbstractDisplayMeta.BillboardConstraints.CENTER);
        meta.setPositionRotationInterpolationDuration(10);
        meta.setTransformationInterpolationDuration(10);
        meta.setShadow(isShadow);
        meta.setViewRange(viewRange);
        meta.setSeeThrough(isSeeThrough);
        String text = String.format(textFormat, value);
        if (replacement != null) {
            text = replacement.replaceAll(text);
        }
        meta.setText(MiniMessage.miniMessage().deserialize(text));
        meta.setTextOpacity(textOpacity);
        meta.setUseDefaultBackground(false);
        meta.setBackgroundColor(backGroundColor);
        meta.setScale(scale.getRandom());
        WrapperPlayServerSpawnEntity packet = new WrapperPlayServerSpawnEntity(
                entityId,
                Optional.of(UUID.randomUUID()),
                EntityTypes.TEXT_DISPLAY,
                new Vector3d(location.getX(), location.getY(), location.getZ()),
                0f, 0f, 0f, 0, Optional.empty()
        );
        PacketUtil.sendPacketToPlayers(packet, players);
        PacketUtil.sendPacketToPlayers(meta.createPacket(), players);
    }


}
