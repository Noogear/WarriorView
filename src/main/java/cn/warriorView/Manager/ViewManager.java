package cn.warriorView.Manager;

import cn.warriorView.View.DamageView;
import org.bukkit.event.entity.EntityDamageEvent;

import java.util.HashMap;
import java.util.Map;

public class ViewManager {

    private final Map<EntityDamageEvent.DamageCause, DamageView> damageViews;

    public ViewManager() {
        this.damageViews = new HashMap<>();
    }

    public Map<EntityDamageEvent.DamageCause, DamageView> getDamageViews() {
        return damageViews;
    }



}
