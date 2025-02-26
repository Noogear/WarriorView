package cn.warriorView.View.Category.Damage;

import cn.warriorView.Object.Animation.IAnimation;
import cn.warriorView.Object.Format.TextFormat;
import cn.warriorView.Object.Offset;
import cn.warriorView.Object.Scale.IScale;
import cn.warriorView.Util.ViewUtil;
import cn.warriorView.View.Category.IDamageDisplay;
import cn.warriorView.View.ViewParams;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class DamageOtherView implements IDamageDisplay {

    final boolean onlyPlayer;
    final Position position;
    final TextFormat textFormat;
    final IScale scale;
    final boolean shadow;
    final byte textOpacity;
    final float viewRange;
    final byte viewMarge;
    final int backgroundColor;
    final boolean seeThrough;
    final List<IAnimation> animations;
    final int length;
    final Offset offset;

    public DamageOtherView(ViewParams params) {
        this.textFormat = params.textFormat();
        this.scale = params.scale();
        this.shadow = params.shadow();
        this.textOpacity = params.textOpacity();
        this.viewRange = params.viewRange();
        this.viewMarge = params.viewMarge();
        this.backgroundColor = params.backgroundColor();
        this.seeThrough = params.seeThrough();
        this.onlyPlayer = params.onlyPlayer();
        this.animations = params.animations();
        this.length = animations.size();
        this.position = Position.valueOf(params.position().toUpperCase());
        this.offset = params.offset();
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
                ViewUtil.spawnDisplay(animation(), shadow, viewRange, viewMarge, seeThrough, textFormat, textOpacity, backgroundColor, scale, entity, attacker, player, damage, offset);
                return;
            case EYE:
                damageLocation = entity.getEyeLocation();
                break;
            default:
                damageLocation = entity.getLocation();
                break;
        }
        ViewUtil.spawnDisplay(animation(), shadow, viewRange, viewMarge, seeThrough, textFormat, textOpacity, backgroundColor, scale, damageLocation, player, damage, offset);

    }

    @Override
    public IAnimation animation() {
        return animations.get(ThreadLocalRandom.current().nextInt(length));
    }

    public enum Position {
        EYE,
        FOOT,
        DAMAGE
    }

}
