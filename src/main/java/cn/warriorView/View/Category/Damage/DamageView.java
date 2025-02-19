package cn.warriorView.View.Category.Damage;

import cn.warriorView.Object.Animation.Animation;
import cn.warriorView.Object.Format.TextFormat;
import cn.warriorView.Object.Scale;
import cn.warriorView.Util.ViewUtil;
import cn.warriorView.View.ViewParams;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;

public class DamageView implements IDamageDisplay {

    final TextFormat textFormat;
    final Scale scale;
    final boolean shadow;
    final byte textOpacity;
    final float viewRange;
    final byte viewMarge;
    final int backgroundColor;
    final boolean seeThrough;
    final boolean onlyPlayer;
    final Animation animation;
    final Position position;

    public DamageView(ViewParams params) {
        this.textFormat = params.textFormat();
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
        LivingEntity entity = (LivingEntity) e.getEntity();
        Player player = null;
        if (entity instanceof Player p) {
            player = p;
        } else {
            if (this.onlyPlayer) return;
        }
        Location damageLocation = (this.position == Position.EYE) ? entity.getEyeLocation() : entity.getLocation();
        ViewUtil.spawnDisplay(animation, shadow, viewRange, viewMarge, seeThrough, textFormat, textOpacity, backgroundColor, scale, damageLocation, player, damage);
    }

    @Override
    public void spawn(EntityDamageByEntityEvent e, double damage) {
        spawn((EntityDamageEvent) e, damage);
    }


    public enum Position {
        EYE,
        FOOT
    }
}
