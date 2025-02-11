package cn.warriorView.View.DamageView;

import cn.warriorView.View.DisplayMethod;
import cn.warriorView.View.ViewParams;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

public class CriticalView extends DamageOtherView {

    public CriticalView(ViewParams params) {
        super(params);
    }

    @Override
    public void spawn(EntityDamageByEntityEvent event, double damage) {
        if (event.getDamager() instanceof Projectile attacker) {
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
            DisplayMethod.spawnDisplay(this, damageLocation, player, damage);
            return;
        }
        super.spawn(event, damage);
    }

}
