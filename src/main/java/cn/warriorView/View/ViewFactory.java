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
            case PROJECTILE -> new ProjectileView(
                    params.textFormat(), params.replacement(), params.scale(),
                    params.shadow(), params.viewRange(), params.viewMarge(),
                    params.backgroundColor(), params.seeThrough(), params.onlyPlayer(),
                    params.animation(), params.position()
            );
            case ENTITY_ATTACK -> new DamageOtherView(
                    params.textFormat(), params.replacement(), params.scale(),
                    params.shadow(), params.viewRange(), params.viewMarge(),
                    params.backgroundColor(), params.seeThrough(), params.onlyPlayer(),
                    params.animation(), params.position()
            );
            default -> new DamageView(
                    params.textFormat(), params.replacement(), params.scale(),
                    params.shadow(), params.viewRange(), params.viewMarge(),
                    params.backgroundColor(), params.seeThrough(), params.onlyPlayer(),
                    params.animation(), params.position());
        };
    }

    public CriticalView createCritical(ViewParams params) {
        return new CriticalView(
                params.textFormat(), params.replacement(), params.scale(),
                params.shadow(), params.viewRange(), params.viewMarge(),
                params.backgroundColor(), params.seeThrough(), params.onlyPlayer(),
                params.animation(), params.position());
    }

    public RegainView createRegain(ViewParams params) {
        return new RegainView(
                params.textFormat(), params.replacement(), params.scale(),
                params.shadow(), params.viewRange(), params.viewMarge(),
                params.backgroundColor(), params.seeThrough(), params.onlyPlayer(),
                params.animation(), params.position());
    }

}
