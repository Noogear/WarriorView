package cn.warriorView.View.Category.Regain;

import cn.warriorView.Object.Animation.IAnimation;
import cn.warriorView.Object.Format.TextFormat;
import cn.warriorView.Object.Offset;
import cn.warriorView.Object.Scale.IScale;
import cn.warriorView.Util.ViewUtil;
import cn.warriorView.View.ViewParams;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityRegainHealthEvent;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class RegainView {
    final boolean onlyPlayer;
    final Position position;
    final int length;
    private final TextFormat textFormat;
    private final IScale scale;
    private final boolean shadow;
    private final byte textOpacity;
    private final float viewRange;
    private final byte viewMarge;
    private final int backgroundColor;
    private final boolean seeThrough;
    private final List<IAnimation> animations;
    private final Offset offset;

    public RegainView(ViewParams params) {
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
        ViewUtil.spawnDisplay(animation(), shadow, viewRange, viewMarge, seeThrough, textFormat, textOpacity, backgroundColor, scale, regainLocation, player, regain, offset);

    }

    private IAnimation animation() {
        return animations.get(ThreadLocalRandom.current().nextInt(length));
    }

    public enum Position {
        EYE,
        FOOT
    }
}
