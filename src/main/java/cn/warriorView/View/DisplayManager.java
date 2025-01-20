package cn.warriorView.View;

import cn.warriorView.Main;
import cn.warriorView.Util.PacketUtil;
import cn.warriorView.Util.Scheduler.XRunnable;
import cn.warriorView.View.DamageView.DamageOtherView;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import me.tofaa.entitylib.meta.EntityMeta;
import me.tofaa.entitylib.meta.display.AbstractDisplayMeta;
import me.tofaa.entitylib.meta.display.TextDisplayMeta;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public class DisplayManager {
    public static DisplayManager instance;
    private final Main plugin;

    public DisplayManager(Main main) {
        plugin = main;
        instance = this;
    }


    public static void spawnDisplay(ViewDisplay viewDisplay, Location location, Set<Player> players, double damage) {
        new XRunnable() {

            @Override
            public void run() {
                int entityId = PacketUtil.getAutoEntityId();
                instance.spawnEntity(viewDisplay, entityId, location, players, damage);
                viewDisplay.getAnimation().play(entityId, PacketUtil.locationToV3d(location), players);
            }
        }.async(instance.plugin);

    }

    public static void spawnDisplay(DamageOtherView viewDisplay, Location entityLocation, Location attackerLocation, Set<Player> players, double damage) {
        new XRunnable() {

            @Override
            public void run() {
                Location damageLocation = attackerLocation.add(attackerLocation.getDirection().normalize().multiply(attackerLocation.distance(entityLocation)));
                int entityId = PacketUtil.getAutoEntityId();
                instance.spawnEntity(viewDisplay, entityId, damageLocation, players, damage);
                viewDisplay.getAnimation().play(entityId, PacketUtil.locationToV3d(damageLocation), players);
            }
        }.async(instance.plugin);

    }

    private void spawnEntity(ViewDisplay viewDisplay, int entityId, Location location, Set<Player> players, double damage) {

        location = location.add(0, 0.22, 0);
        TextDisplayMeta meta = (TextDisplayMeta) EntityMeta.createMeta(entityId, EntityTypes.TEXT_DISPLAY);
        meta.setBillboardConstraints(AbstractDisplayMeta.BillboardConstraints.CENTER);
        meta.setPositionRotationInterpolationDuration(10);
        meta.setTransformationInterpolationDuration(10);
        meta.setShadow(viewDisplay.isShadow());
        meta.setViewRange(viewDisplay.getViewRange());
        meta.setSeeThrough(viewDisplay.isSeeThrough());
        String text = String.format(viewDisplay.getTextFormat(), damage);
        meta.setText(MiniMessage.miniMessage().deserialize(viewDisplay.getReplacement().replaceAll(text)));
        meta.setUseDefaultBackground(false);
        meta.setBackgroundColor(viewDisplay.getBackgroundColor());
        float scale = viewDisplay.getScale();
        if (scale != 1) {
            meta.setScale(new Vector3f(scale, scale, scale));
        }
        WrapperPlayServerSpawnEntity packet = new WrapperPlayServerSpawnEntity(
                entityId,
                Optional.of(UUID.randomUUID()),
                EntityTypes.TEXT_DISPLAY,
                new Vector3d(location.getX(), location.getY(), location.getZ()),
                0f, 0f, 0f, 0, Optional.empty()
        );
        PacketUtil.sendPacketListPlayer(packet, players);
        PacketUtil.sendPacketListPlayer(meta.createPacket(), players);

    }


}
