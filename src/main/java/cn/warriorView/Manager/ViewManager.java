package cn.warriorView.Manager;

import cn.warriorView.View.DamageView.DamageView;
import cn.warriorView.View.RegainView.RegainView;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;

import java.util.HashMap;
import java.util.Map;

public class ViewManager {

    private final Map<EntityDamageEvent.DamageCause, DamageView> damageViews;

    private final Map<EntityRegainHealthEvent.RegainReason, RegainView> regainViews;

    public ViewManager() {
        this.damageViews = new HashMap<>();
        this.regainViews = new HashMap<>();
    }

    public Map<EntityDamageEvent.DamageCause, DamageView> getDamageViews() {
        return damageViews;
    }

    public Map<EntityRegainHealthEvent.RegainReason, RegainView> getRegainViews() {
        return regainViews;
    }



}
