package cn.warriorView.View.DamageView;

import cn.warriorView.View.ViewParams;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

public class CriticalView extends DamageOtherView {

    public CriticalView(ViewParams params) {
        super(params);
    }

    @Override
    public void spawn(EntityDamageByEntityEvent event) {
        super.spawn(event);
    }

}
