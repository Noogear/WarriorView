package cn.warriorView.Manager;

import cn.warriorView.View.DamageView.CriticalView;
import cn.warriorView.View.DamageView.DamageOtherView;
import cn.warriorView.View.DamageView.DamageView;
import cn.warriorView.View.DamageView.ProjectileView;
import cn.warriorView.View.RegainView.RegainView;
import cn.warriorView.View.ViewDisplay;
import cn.warriorView.View.ViewParams;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;

import java.util.HashMap;
import java.util.Map;

public class ViewManager {

    private final Map<EntityDamageEvent.DamageCause, ViewDisplay> damageViews;
    private final Map<EntityRegainHealthEvent.RegainReason, ViewDisplay> regainViews;
    private CriticalView criticalView;

    public ViewManager() {
        this.damageViews = new HashMap<>();
        this.regainViews = new HashMap<>();
    }

    public Map<EntityDamageEvent.DamageCause, ViewDisplay> getDamageViews() {
        return damageViews;
    }

    public void addDamageViews(EntityDamageEvent.DamageCause cause, ViewParams params) {
        switch (cause) {
            case PROJECTILE -> damageViews.put(cause, new ProjectileView(params));
            case ENTITY_ATTACK, ENTITY_SWEEP_ATTACK, ENTITY_EXPLOSION ->
                    damageViews.put(cause, new DamageOtherView(params));
            default -> damageViews.put(cause, new DamageView(params));
        }
    }

    public Map<EntityRegainHealthEvent.RegainReason, ViewDisplay> getRegainViews() {
        return regainViews;
    }

    public void addRegainViews(EntityRegainHealthEvent.RegainReason reason, ViewParams params) {
        regainViews.put(reason, new RegainView(params));
    }

    public CriticalView getCriticalView() {
        return criticalView;
    }

    public void setCriticalView(ViewParams params) {
        criticalView = new CriticalView(params);
    }

    public void init() {
        damageViews.clear();
        regainViews.clear();
    }
}
