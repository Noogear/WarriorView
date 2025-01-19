package cn.warriorView.Listener;

import cn.warriorView.Main;
import cn.warriorView.Util.PacketUtil;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import me.tofaa.entitylib.meta.EntityMeta;
import me.tofaa.entitylib.meta.display.AbstractDisplayMeta;
import me.tofaa.entitylib.meta.display.TextDisplayMeta;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.*;

public class EntityDamageOther implements Listener {
    private final Main plugin;

    public EntityDamageOther(Main main) {
        this.plugin = main;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onDamage(EntityDamageByEntityEvent event) {
        double damage = event.getFinalDamage();
        if (damage <= 0.01) return;
        if (!(event.getEntity() instanceof LivingEntity entity)) return;

        EntityDamageEvent.DamageCause cause = event.getCause();
        Location entityLocation = entity.getEyeLocation();
        Location attackerLocation;
        Entity damager = event.getDamager();
        Player player = null;
        Vector velocity;
        boolean isProject;
        boolean isCritical = event.isCritical();

        if (damager instanceof LivingEntity attacker) {
            isProject = false;
            velocity = null;
            attackerLocation = attacker.getEyeLocation();
            if(attacker instanceof Player p) {
                player = p;
            }
        } else {
            if (damager instanceof Projectile attacker) {
                isProject = true;
                attackerLocation = attacker.getLocation();
                velocity = attacker.getVelocity();
                if(attacker.getShooter() instanceof Player p) {
                    player = p;
                }
            } else {
                return;
            }
        }

        Set<Player> players = new HashSet<>(entityLocation.getNearbyPlayers(16));
        if(player != null) {
            players.add(player);
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {

            Location damageLocation;
            if (!isProject) {
                if (cause == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK) {
                    damageLocation = entityLocation;
                } else {
                    damageLocation = attackerLocation.add(attackerLocation.getDirection().normalize().multiply(attackerLocation.distance(entityLocation)));
                }
            } else {
                damageLocation = attackerLocation.add(velocity);
            }
            damageLocation = damageLocation.add(0, 0.25, 0);

            int id = PacketUtil.getAutoEntityId();
            TextDisplayMeta meta = (TextDisplayMeta) EntityMeta.createMeta(id, EntityTypes.TEXT_DISPLAY);
            meta.setText(Component.text(String.format("\uD83D\uDDE1%.1f",damage)));
            if(isCritical){
                meta.setScale(new Vector3f(1.5F, 1.5F, 1.5F));
            }
            meta.setBillboardConstraints(AbstractDisplayMeta.BillboardConstraints.CENTER);
            meta.setPositionRotationInterpolationDuration(10);
            meta.setTransformationInterpolationDuration(10);
            meta.setViewRange(0.2f);
            meta.setShadow(true);
            Vector3d location = new Vector3d(damageLocation.getX(), damageLocation.getY(), damageLocation.getZ());
            WrapperPlayServerSpawnEntity packet = new WrapperPlayServerSpawnEntity(
                    id,
                    Optional.of(UUID.randomUUID()),
                    EntityTypes.TEXT_DISPLAY,
                    location,
                    0f, 0f, 0f, 0, Optional.empty()
            );
            PacketUtil.sendPacketListPlayer(packet, players);
            PacketUtil.sendPacketListPlayer(meta.createPacket(), players);

            new BukkitRunnable() {
                byte count = 0;
                double changeY = 1;

                @Override
                public void run() {
                    Vector3d tpLocation = location.add(0, (count+1) * changeY, 0);
                    PacketUtil.sendPacketListPlayer(new WrapperPlayServerEntityTeleport(
                            id,
                            tpLocation,
                            0f,
                            0f,
                            false
                    ), players);

                    if (count >= 9) {
                        PacketUtil.sendPacketListPlayer(new WrapperPlayServerDestroyEntities(id), players);
                        cancel();
                        players.clear();
                        return;
                    }
                    count++;
                    changeY -= 0.17;
                }
            }.runTaskTimerAsynchronously(plugin, 2, 2);
        });

    }


}
