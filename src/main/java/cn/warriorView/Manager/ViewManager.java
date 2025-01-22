package cn.warriorView.Manager;

import cn.warriorView.View.DamageView.CriticalView;
import cn.warriorView.View.ViewDisplay;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;

import java.util.HashMap;
import java.util.Map;

public class ViewManager {

    private CriticalView criticalView;
    private final Map<EntityDamageEvent.DamageCause, ViewDisplay> damageViews;
    private final Map<EntityRegainHealthEvent.RegainReason, ViewDisplay> regainViews;

    public ViewManager() {
        this.damageViews = new HashMap<>();
        this.regainViews = new HashMap<>();
        init();
    }

    public Map<EntityDamageEvent.DamageCause, ViewDisplay> getDamageViews() {
        return damageViews;
    }

    public void addDamageViews(EntityDamageEvent.DamageCause cause, ViewDisplay damageView) {
        this.damageViews.put(cause, damageView);
    }

    public Map<EntityRegainHealthEvent.RegainReason, ViewDisplay> getRegainViews() {
        return regainViews;
    }

    public void addRegainViews(EntityRegainHealthEvent.RegainReason reason, ViewDisplay regainView) {
        this.regainViews.put(reason, regainView);
    }

    public CriticalView getCriticalView() {
        return criticalView;
    }

    public void setCriticalView(CriticalView criticalView) {
        this.criticalView = criticalView;
    }

    public void init() {
        damageViews.clear();
        regainViews.clear();
    }
}
