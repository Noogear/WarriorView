package cn.warriorView.View.DamageView;

import cn.warriorView.Object.Range;
import cn.warriorView.Object.Replacement;
import cn.warriorView.View.Animation.Animation;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

public class CriticalView extends DamageOtherView {
    public CriticalView(String textFormat, Range scale, Animation animation, boolean onlyPlayer, Replacement replacement, boolean shadow, float viewRange, byte viewMarge, int backgroundColor, boolean seeThrough, Position position) {
        super(textFormat, scale, animation, onlyPlayer, replacement, shadow, viewRange, viewMarge, backgroundColor, seeThrough, position);
    }

    public void spawn(EntityDamageByEntityEvent event){}

}
