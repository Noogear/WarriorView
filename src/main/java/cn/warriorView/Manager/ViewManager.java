package cn.warriorView.manager;

import cn.warriorView.view.ViewParams;
import cn.warriorView.view.category.IDamageDisplay;
import cn.warriorView.view.category.damage.CriticalView;
import cn.warriorView.view.category.damage.DamageOtherView;
import cn.warriorView.view.category.damage.DamageView;
import cn.warriorView.view.category.damage.ProjectileView;
import cn.warriorView.view.category.regain.RegainView;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;

import java.util.EnumMap;

public class ViewManager {

    private final EnumMap<EntityDamageEvent.DamageCause, IDamageDisplay> damageViews;
    private final EnumMap<EntityRegainHealthEvent.RegainReason, RegainView> regainViews;
    private CriticalView criticalView;

    public ViewManager() {
        this.damageViews = new EnumMap<>(EntityDamageEvent.DamageCause.class);
        this.regainViews = new EnumMap<>(EntityRegainHealthEvent.RegainReason.class);
    }

    public EnumMap<EntityDamageEvent.DamageCause, IDamageDisplay> getDamageViews() {
        return damageViews;
    }

    public void addDamageViews(EntityDamageEvent.DamageCause cause, ViewParams params) {
        switch (cause) {
            case PROJECTILE -> damageViews.put(cause, new ProjectileView(params));
            case ENTITY_ATTACK, ENTITY_SWEEP_ATTACK -> damageViews.put(cause, new DamageOtherView(params));
            default -> damageViews.put(cause, new DamageView(params));
        }
    }

    public EnumMap<EntityRegainHealthEvent.RegainReason, RegainView> getRegainViews() {
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
