package cn.warriorView.View.DamageView;

import cn.warriorView.Object.Range;
import cn.warriorView.Object.Replacement;
import cn.warriorView.View.DisplayManager;
import cn.warriorView.View.Animation.Animation;
import cn.warriorView.View.ViewDisplay;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

import java.util.HashSet;
import java.util.Set;

public class DamageOtherView extends ViewDisplay {

    private final Position position;
    private final boolean onlyPlayer;

    public DamageOtherView(String textFormat, Range scale, Animation animation, boolean onlyPlayer, Replacement replacement, boolean shadow, float viewRange, byte viewMarge, int backgroundColor, boolean seeThrough, Position position) {
        super(textFormat, scale, animation, replacement, shadow, viewRange, viewMarge, backgroundColor, seeThrough);
        this.onlyPlayer = onlyPlayer;
        this.position = position;
    }

    public Position getPosition() {
        return position;
    }

    public boolean isOnlyPlayer() {
        return onlyPlayer;
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
        boolean isCritical = event.isCritical();
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
