package cn.warriorView.Manager;

import cn.warriorView.View.DamageView.CriticalView;
import cn.warriorView.View.DamageView.DamageView;
import cn.warriorView.View.DefaultView;
import cn.warriorView.View.RegainView.RegainView;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;

import java.util.HashMap;
import java.util.Map;

public class ViewManager {
    
    private DefaultView defaultDamageView;
    private DefaultView defaultRegainView;
    private final Map<EntityDamageEvent.DamageCause, DamageView> damageViews;
    private final Map<EntityRegainHealthEvent.RegainReason, RegainView> regainViews;
    private CriticalView criticalView;

    public ViewManager() {
        this.damageViews = new HashMap<>();
        this.regainViews = new HashMap<>();
        load();
    }

    public Map<EntityDamageEvent.DamageCause, DamageView> getDamageViews() {
        return damageViews;
    }

    public void addDamageViews(EntityDamageEvent.DamageCause cause, DamageView damageView) {
        this.damageViews.put(cause, damageView);
    }

    public Map<EntityRegainHealthEvent.RegainReason, RegainView> getRegainViews() {
        return regainViews;
    }

    public void addRegainViews(EntityRegainHealthEvent.RegainReason reason, RegainView regainView) {
        this.regainViews.put(reason, regainView);
    }

    public CriticalView getCriticalView() {
        return criticalView;
    }

    public void setCriticalView(CriticalView criticalView) {
        this.criticalView = criticalView;
    }

    public DefaultView getDefaultDamageView() {
        return defaultDamageView;
    }

    public void setDefaultDamageView(DefaultView defaultDamageView) {
        this.defaultDamageView = defaultDamageView;
    }

    public DefaultView getDefaultRegainView() {
        return defaultRegainView;
    }

    public void setDefaultRegainView(DefaultView defaultRegainView) {
        this.defaultRegainView = defaultRegainView;
    }

    private void load() {

    }
}
