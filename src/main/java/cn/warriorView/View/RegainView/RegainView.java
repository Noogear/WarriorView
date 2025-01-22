package cn.warriorView.View.RegainView;

import cn.warriorView.Object.Animation.Animation;
import cn.warriorView.Object.Range;
import cn.warriorView.Object.Replacement;
import cn.warriorView.View.DisplayManager;
import cn.warriorView.View.ViewDisplay;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityRegainHealthEvent;

public class RegainView extends ViewDisplay {

    private final Position position;

    public RegainView(String textFormat, Replacement replacement, Range scale, boolean shadow, float viewRange, byte viewMarge, int backgroundColor, boolean seeThrough, boolean onlyPlayer, Animation animation, String position) {
        super(textFormat, replacement, scale, shadow, viewRange, viewMarge, backgroundColor, seeThrough, onlyPlayer, animation);
        this.position = Position.valueOf(position.toUpperCase()) ;
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
        DisplayManager.spawnDisplay(this, regainLocation, player, regain);

    }


    public Position getPosition() {
        return position;
    }

    public enum Position {
        EYE,
        FOOT
    }

}
