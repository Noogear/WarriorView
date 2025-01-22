package cn.warriorView.View.DamageView;

import cn.warriorView.View.DisplayManager;
import cn.warriorView.View.ViewParams;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

public class ProjectileView extends DamageOtherView {

    public ProjectileView(ViewParams params) {
        super(params);
    }

    @Override
    public void spawn(EntityDamageByEntityEvent event) {
        double damage = event.getFinalDamage();
        if (damage <= 0.01) return;
        Projectile attacker = (Projectile) event.getDamager();
        Player player = null;
        if (attacker.getShooter() instanceof Player p) {
            player = p;
        } else {
            if (this.isOnlyPlayer()) return;
        }
        LivingEntity entity = (LivingEntity) event.getEntity();
        Location damageLocation = switch (this.getPosition()) {
            case DAMAGE -> attacker.getLocation().add(attacker.getVelocity());
            case EYE -> entity.getEyeLocation();
            default -> entity.getLocation();
        };
        DisplayManager.spawnDisplay(this, damageLocation, player, damage);
    }

}
