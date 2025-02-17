package cn.warriorView.View.Category.Damage;

import cn.warriorView.Object.Animation.Animation;
import cn.warriorView.Object.TextFormat.Replacement;
import cn.warriorView.Object.Scale;
import cn.warriorView.Util.ViewUtil;
import cn.warriorView.View.ViewParams;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;

public class DamageOtherView implements IDamageDisplay {

    final boolean onlyPlayer;
    final Position position;
    final String textFormat;
    final Replacement replacement;
    final Scale scale;
    final boolean shadow;
    final byte textOpacity;
    final float viewRange;
    final byte viewMarge;
    final int backgroundColor;
    final boolean seeThrough;
    final Animation animation;


    public DamageOtherView(ViewParams params) {
        this.textFormat = params.textFormat();
        this.replacement = params.replacement();
        this.scale = params.scale();
        this.shadow = params.shadow();
        this.textOpacity = params.textOpacity();
        this.viewRange = params.viewRange();
        this.viewMarge = params.viewMarge();
        this.backgroundColor = params.backgroundColor();
        this.seeThrough = params.seeThrough();
        this.onlyPlayer = params.onlyPlayer();
        this.animation = params.animation();
        this.position = Position.valueOf(params.position().toUpperCase());
    }


    @Override
    public void spawn(EntityDamageEvent e, double damage) {
        spawn((EntityDamageByEntityEvent) e, damage);
    }

    @Override
    public void spawn(EntityDamageByEntityEvent event, double damage) {
        LivingEntity attacker = (LivingEntity) event.getDamager();
        Player player = null;
        if (attacker instanceof Player p) {
            player = p;
        } else {
            if (this.onlyPlayer) return;
        }
        LivingEntity entity = (LivingEntity) event.getEntity();
        Location damageLocation;
        switch (this.position) {
            case DAMAGE:
                ViewUtil.spawnDisplay(animation, shadow, viewRange, viewMarge, seeThrough, textFormat, replacement, textOpacity, backgroundColor, scale, entity, attacker, player, damage);
                return;
            case EYE:
                damageLocation = entity.getEyeLocation();
                break;
            default:
                damageLocation = entity.getLocation();
                break;
        }
        ViewUtil.spawnDisplay(animation, shadow, viewRange, viewMarge, seeThrough, textFormat, replacement, textOpacity, backgroundColor, scale, damageLocation, player, damage);

    }

    public enum Position {
        EYE,
        FOOT,
        DAMAGE
    }

}
