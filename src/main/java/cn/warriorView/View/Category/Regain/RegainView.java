package cn.warriorView.View.Category.Regain;

import cn.warriorView.Object.Animation.Animation;
import cn.warriorView.Object.Replace;
import cn.warriorView.Object.Scale;
import cn.warriorView.Util.ViewUtil;
import cn.warriorView.View.ViewParams;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityRegainHealthEvent;

public class RegainView {
    final boolean onlyPlayer;
    final Position position;
    private final String textFormat;
    private final Replace replace;
    private final Scale scale;
    private final boolean shadow;
    private final byte textOpacity;
    private final float viewRange;
    private final byte viewMarge;
    private final int backgroundColor;
    private final boolean seeThrough;
    private final Animation animation;

    public RegainView(ViewParams params) {
        this.textFormat = params.textFormat();
        this.replace = params.replace();
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

    public void spawn(EntityRegainHealthEvent event) {
        double regain = event.getAmount();
        if (regain <= 0.01) return;
        LivingEntity entity = (LivingEntity) event.getEntity();
        Player player = null;
        if (entity instanceof Player p) {
            player = p;
        } else {
            if (this.onlyPlayer) return;
        }
        Location regainLocation = (this.position == Position.EYE) ? entity.getEyeLocation() : entity.getLocation();
        ViewUtil.spawnDisplay(animation, shadow, viewRange, viewMarge, seeThrough, textFormat, replace, textOpacity, backgroundColor, scale, regainLocation, player, regain);

    }

    public Position getPosition() {
        return position;
    }

    public enum Position {
        EYE,
        FOOT
    }
}
