package cn.warriorView.Manager;

import cn.warriorView.View.Category.Damage.*;
import cn.warriorView.View.Category.Regain.RegainView;
import cn.warriorView.View.ViewParams;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;

import java.util.EnumMap;
import java.util.Map;

public class ViewManager {

    private final Map<EntityDamageEvent.DamageCause, IDamageDisplay> damageViews;
    private final Map<EntityRegainHealthEvent.RegainReason, RegainView> regainViews;
    private CriticalView criticalView;

    public ViewManager() {
        this.damageViews = new EnumMap<>(EntityDamageEvent.DamageCause.class);
        this.regainViews = new EnumMap<>(EntityRegainHealthEvent.RegainReason.class);
    }

    public Map<EntityDamageEvent.DamageCause, IDamageDisplay> getDamageViews() {
        return damageViews;
    }

    public void addDamageViews(EntityDamageEvent.DamageCause cause, ViewParams params) {
        switch (cause) {
            case PROJECTILE -> damageViews.put(cause, new ProjectileView(params));
            case ENTITY_ATTACK, ENTITY_SWEEP_ATTACK -> damageViews.put(cause, new DamageOtherView(params));
            default -> damageViews.put(cause, new DamageView(params));
        }
    }

    public Map<EntityRegainHealthEvent.RegainReason, RegainView> getRegainViews() {
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
