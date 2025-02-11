package cn.warriorView.View.RegainView;

import cn.warriorView.View.DisplayMethod;
import cn.warriorView.View.ViewDisplay;
import cn.warriorView.View.ViewParams;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityRegainHealthEvent;

public class RegainView extends ViewDisplay {

    private final Position position;

    public RegainView(ViewParams params) {
        super(params);
        this.position = Position.valueOf(params.position().toUpperCase());
    }

    public void spawn(EntityRegainHealthEvent event) {
        double regain = event.getAmount();
        if (regain <= 0.01) return;
        LivingEntity entity = (LivingEntity) event.getEntity();
        Player player = null;
        if (entity instanceof Player p) {
            player = p;
        } else {
            if (this.isOnlyPlayer()) return;
        }
        Location regainLocation = (this.getPosition() == Position.EYE) ? entity.getEyeLocation() : entity.getLocation();
        DisplayMethod.spawnDisplay(this, regainLocation, player, regain);

    }

    public Position getPosition() {
        return position;
    }

    public enum Position {
        EYE,
        FOOT
    }

}
