package cn.warriorView.View;

import cn.warriorView.Object.Range;
import cn.warriorView.Object.Replacement;
import cn.warriorView.Util.PacketUtil;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

import java.util.HashSet;
import java.util.Set;

public class DamageOtherView extends ViewDisplay {

    private final Position position;
    private final boolean onlyPlayer;

    public DamageOtherView(String textFormat, Range scale, byte removeCount, boolean onlyPlayer, Replacement replacement, boolean shadow, double viewRange, byte viewMarge, int backgroundColor, Position position) {
        super(textFormat, scale, removeCount, replacement, shadow, viewRange, viewMarge, backgroundColor);
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
                PacketUtil.spawnDisplay(this, entityEyeLocation, attacker.getEyeLocation(), players);
                return;
            case EYE:
                damageLocation = entityEyeLocation;
                break;
            default:
                damageLocation = entity.getLocation();
                break;
        }
        PacketUtil.spawnDisplay(this, damageLocation, players);
    }

    public enum Position {
        EYE,
        FOOT,
        DAMAGE
    }


}
