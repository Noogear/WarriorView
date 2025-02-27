package cn.warriorView.view.category.damage;

import cn.warriorView.util.ViewUtil;
import cn.warriorView.view.ViewParams;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;

import java.util.ArrayList;

public class CriticalView extends DamageOtherView {

    public CriticalView(ViewParams params) {
        super(params);
    }

    @Override
    public void spawn(EntityDamageByEntityEvent event, double damage) {
        if (event.getCause() == EntityDamageEvent.DamageCause.PROJECTILE) {
            Projectile attacker = (Projectile) event.getDamager();
            Player player = null;
            if (attacker.getShooter() instanceof Player p) {
                player = p;
            } else {
                if (this.onlyPlayer) return;
            }
            LivingEntity entity = (LivingEntity) event.getEntity();
            Location damageLocation = switch (this.position) {
                case DAMAGE -> attacker.getLocation().add(attacker.getVelocity());
                case EYE -> entity.getEyeLocation();
                default -> entity.getLocation();
            };
            ViewUtil.spawnDisplay(animation(), viewMarge, textFormat, scale, damageLocation, player, damage, offset, new ArrayList<>(basicSpawnData));
            return;
        }
        super.spawn(event, damage);
    }
}
