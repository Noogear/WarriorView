package cn.warriorView.View.DamageView;

import cn.warriorView.View.DisplayManager;
import cn.warriorView.View.ViewDisplay;
import cn.warriorView.View.ViewParams;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;

public class DamageView extends ViewDisplay {

    private final Position position;

    public DamageView(ViewParams params) {
        super(params);
        this.position = Position.valueOf(params.position().toUpperCase());
    }

    public Position getPosition() {
        return position;
    }

    public void spawn(EntityDamageEvent event) {
        double damage = event.getFinalDamage();
        if (damage <= 0.01) return;
        LivingEntity entity = (LivingEntity) event.getEntity();
        Player player = null;
        if (entity instanceof Player p) {
            player = p;
        } else {
            if (this.isOnlyPlayer()) return;
        }
        Location damageLocation = (this.getPosition() == Position.EYE) ? entity.getEyeLocation() : entity.getLocation();
        DisplayManager.spawnDisplay(this, damageLocation, player, damage);
    }

    public enum Position {
        EYE,
        FOOT
    }


}
