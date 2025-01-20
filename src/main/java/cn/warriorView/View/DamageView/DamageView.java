package cn.warriorView.View.DamageView;

import cn.warriorView.Object.Range;
import cn.warriorView.Object.Replacement;
import cn.warriorView.View.Animation.Animation;
import cn.warriorView.View.ViewDisplay;
import org.bukkit.event.entity.EntityDamageEvent;

public class DamageView extends ViewDisplay {

    private final Position position;

    public DamageView(String textFormat, Range scale, Animation animation, Replacement replacement, boolean shadow, float viewRange, byte viewMarge, int backgroundColor, boolean seeThrough, Position position) {
        super(textFormat, scale, animation, replacement, shadow, viewRange, viewMarge, backgroundColor, seeThrough);
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
