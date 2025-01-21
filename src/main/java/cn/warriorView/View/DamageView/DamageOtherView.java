package cn.warriorView.View.DamageView;

import cn.warriorView.Object.Range;
import cn.warriorView.Object.Replacement;
import cn.warriorView.View.DisplayManager;
import cn.warriorView.Object.Animation.Animation;
import cn.warriorView.View.ViewDisplay;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

import java.util.HashSet;
import java.util.Set;

public class DamageOtherView extends ViewDisplay {

    private final Position position;

    protected DamageOtherView(String textFormat, Replacement replacement, Range scale, boolean shadow, float viewRange, byte viewMarge, int backgroundColor, boolean seeThrough, boolean onlyPlayer, Animation animation, Position position) {
        super(textFormat, replacement, scale, shadow, viewRange, viewMarge, backgroundColor, seeThrough, onlyPlayer, animation);
        this.position = position;
    }

    public Position getPosition() {
        return position;
    }


    public void spawn(EntityDamageByEntityEvent event) {
        double damage = event.getFinalDamage();
        if(damage <= 0.01) return;
        LivingEntity attacker = (LivingEntity) event.getDamager();
        Set<Player> players = new HashSet<>();
        if (attacker instanceof Player p) {
            players.add(p);
        } else {
            if (this.isOnlyPlayer()) return;
        }
        LivingEntity entity = (LivingEntity) event.getEntity();
        Location entityEyeLocation = entity.getEyeLocation();
        players.addAll(entityEyeLocation.getNearbyPlayers(this.getViewMarge()));
        Location damageLocation;
        switch (this.getPosition()) {
            case DAMAGE:
                DisplayManager.spawnDisplay(this, entityEyeLocation, attacker.getEyeLocation(), players,damage);
                return;
            case EYE:
                damageLocation = entityEyeLocation;
                break;
            default:
                damageLocation = entity.getLocation();
                break;
        }
        DisplayManager.spawnDisplay(this, damageLocation, players,damage);
    }

    public enum Position {
        EYE,
        FOOT,
        DAMAGE
    }


}
