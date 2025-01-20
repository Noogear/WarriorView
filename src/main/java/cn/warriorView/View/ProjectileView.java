package cn.warriorView.View;

import cn.warriorView.Object.Range;
import cn.warriorView.Object.Replacement;
import cn.warriorView.Util.PacketUtil;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

import java.util.HashSet;
import java.util.Set;

public class ProjectileView extends DamageOtherView {

    public ProjectileView(String textFormat, Range scale, byte removeCount, boolean onlyPlayer, Replacement replacement, boolean shadow, double viewRange, byte viewMarge, int backgroundColor, Position position) {
        super(textFormat, scale, removeCount, onlyPlayer, replacement, shadow, viewRange, viewMarge, backgroundColor, position);
    }

    @Override
    public void spawn(EntityDamageByEntityEvent event) {
        Projectile attacker = (Projectile) event.getDamager();
        Set<Player> players = new HashSet<>();
        if (attacker.getShooter() instanceof Player p) {
            players.add(p);
        } else {
            if (this.isOnlyPlayer()) return;
        }
        LivingEntity entity = (LivingEntity) event.getEntity();
        boolean isCritical = event.isCritical();
        Location damageLocation;
        damageLocation = switch (this.getPosition()) {
            case DAMAGE -> attacker.getLocation().add(attacker.getVelocity());
            case EYE -> entity.getEyeLocation();
            default -> attacker.getLocation();
        };
        players.addAll(damageLocation.getNearbyPlayers(this.getViewMarge()));
        PacketUtil.spawnDisplay(this, damageLocation, players);
    }

}
