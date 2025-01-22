package cn.warriorView.View.DamageView;

import cn.warriorView.Object.Range;
import cn.warriorView.Object.Replacement;
import cn.warriorView.Object.Animation.Animation;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

public class CriticalView extends DamageOtherView {

    public CriticalView(String textFormat, Replacement replacement, Range scale, boolean shadow, float viewRange, byte viewMarge, int backgroundColor, boolean seeThrough, boolean onlyPlayer, Animation animation, Position position) {
        super(textFormat, replacement, scale, shadow, viewRange, viewMarge, backgroundColor, seeThrough, onlyPlayer, animation, position);
    }

    @Override
    public void spawn(EntityDamageByEntityEvent event){
        super.spawn(event);
    }

}
