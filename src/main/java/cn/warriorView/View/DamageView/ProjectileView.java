package cn.warriorView.View.DamageView;

import cn.warriorView.Object.Range;
import cn.warriorView.Object.Replacement;
import cn.warriorView.Object.Animation.Animation;
import cn.warriorView.View.DisplayManager;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

import java.util.HashSet;
import java.util.Set;

public class ProjectileView extends DamageOtherView {

    protected ProjectileView(String textFormat, Replacement replacement, Range scale, boolean shadow, float viewRange, byte viewMarge, int backgroundColor, boolean seeThrough, boolean onlyPlayer, Animation animation, Position position) {
        super(textFormat, replacement, scale, shadow, viewRange, viewMarge, backgroundColor, seeThrough, onlyPlayer, animation, position);
    }

    @Override
    public void spawn(EntityDamageByEntityEvent event) {
        double damage = event.getFinalDamage();
        if (damage <= 0.01) return;
        Projectile attacker = (Projectile) event.getDamager();
        Set<Player> players = new HashSet<>();
        if (attacker.getShooter() instanceof Player p) {
            players.add(p);
        } else {
            if (this.isOnlyPlayer()) return;
        }
        LivingEntity entity = (LivingEntity) event.getEntity();
        Location damageLocation;
        damageLocation = switch (this.getPosition()) {
            case DAMAGE -> attacker.getLocation().add(attacker.getVelocity());
            case EYE -> entity.getEyeLocation();
            default -> attacker.getLocation();
        };
        players.addAll(damageLocation.getNearbyPlayers(this.getViewMarge()));
        DisplayManager.spawnDisplay(this, damageLocation, players, damage);
    }

}
