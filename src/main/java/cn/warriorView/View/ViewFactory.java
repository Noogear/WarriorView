package cn.warriorView.View;

import cn.warriorView.View.DamageView.CriticalView;
import cn.warriorView.View.DamageView.DamageOtherView;
import cn.warriorView.View.DamageView.DamageView;
import cn.warriorView.View.DamageView.ProjectileView;
import cn.warriorView.View.RegainView.RegainView;
import org.bukkit.event.entity.EntityDamageEvent;

public class ViewFactory {

    public ViewDisplay createDamage(ViewParams params, EntityDamageEvent.DamageCause cause) {
        return switch (cause) {
            case PROJECTILE -> new ProjectileView(params);
            case ENTITY_ATTACK -> new DamageOtherView(params);
            default -> new DamageView(params);
        };
    }

    public CriticalView createCritical(ViewParams params) {
        return new CriticalView(params);
    }

    public RegainView createRegain(ViewParams params) {
        return new RegainView(params);
    }

}
