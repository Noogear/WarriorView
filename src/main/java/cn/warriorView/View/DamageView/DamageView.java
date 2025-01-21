package cn.warriorView.View.DamageView;

import cn.warriorView.Object.Range;
import cn.warriorView.Object.Replacement;
import cn.warriorView.Object.Animation.Animation;
import cn.warriorView.View.ViewDisplay;
import org.bukkit.event.entity.EntityDamageEvent;

public class DamageView extends ViewDisplay {

    private final Position position;

    protected DamageView(String textFormat, Replacement replacement, Range scale, boolean shadow, float viewRange, byte viewMarge, int backgroundColor, boolean seeThrough, boolean onlyPlayer, Animation animation, Position position) {
        super(textFormat, replacement, scale, shadow, viewRange, viewMarge, backgroundColor, seeThrough, onlyPlayer, animation);
        this.position = position;
    }


    public Position getPosition() {
        return position;
    }

    public void spawn(EntityDamageEvent event) {

    }

    public enum Position {
        EYE,
        FOOT
    }


}
