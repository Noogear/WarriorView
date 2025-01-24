package cn.warriorView.View.DamageView;

import cn.warriorView.View.DisplayManager;
import cn.warriorView.View.ViewDisplay;
import cn.warriorView.View.ViewParams;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

public class DamageOtherView extends ViewDisplay {

    private final Position position;

    public DamageOtherView(ViewParams params) {
        super(params);
        this.position = Position.valueOf(params.position().toUpperCase());
    }

    public Position getPosition() {
        return position;
    }

    public void spawn(EntityDamageByEntityEvent event, double damage) {
        LivingEntity attacker = (LivingEntity) event.getDamager();
        Player player = null;
        if (attacker instanceof Player p) {
            player = p;
        } else {
            if (this.isOnlyPlayer()) return;
        }
        LivingEntity entity = (LivingEntity) event.getEntity();
        Location damageLocation;
        switch (this.getPosition()) {
            case DAMAGE:
                DisplayManager.spawnDisplay(this, entity, attacker, player, damage);
                return;
            case EYE:
                damageLocation = entity.getEyeLocation();
                break;
            default:
                damageLocation = entity.getLocation();
                break;
        }
        DisplayManager.spawnDisplay(this, damageLocation, player, damage);
    }

    public enum Position {
        EYE,
        FOOT,
        DAMAGE
    }


}
