package cn.warriorView.View.RegainView;

import cn.warriorView.Object.Range;
import cn.warriorView.Object.Replacement;
import cn.warriorView.Object.Animation.Animation;
import cn.warriorView.View.ViewDisplay;
import org.bukkit.event.entity.EntityRegainHealthEvent;

public class RegainView extends ViewDisplay {

    private final Position position;
    private final boolean onlyPlayer;

    public RegainView(String textFormat, Range scale, Animation animation, boolean onlyPlayer, Replacement replacement, boolean shadow, float viewRange, byte viewMarge, int backgroundColor, boolean seeThrough, Position position) {
        super(textFormat, scale, animation, replacement, shadow, viewRange, viewMarge, backgroundColor, seeThrough);
        this.position = position;
        this.onlyPlayer = onlyPlayer;
    }

    public void spawn(EntityRegainHealthEvent event){

    }

    public Position getPosition() {
        return position;
    }

    public boolean isOnlyPlayer() {
        return onlyPlayer;
    }

    public enum Position {
        EYE,
        FOOT
    }

}
