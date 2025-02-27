package cn.warriorView.listener;

import cn.warriorView.Main;
import cn.warriorView.view.category.IDamageDisplay;
import cn.warriorView.view.category.damage.CriticalView;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;

import java.util.EnumMap;

public class EntityDamage implements Listener {
    private final EnumMap<EntityDamageEvent.DamageCause, IDamageDisplay> damageViews;
    private final CriticalView criticalView;
    private final boolean criticalEnabled;

    public EntityDamage(Main main) {
        this.damageViews = main.getViewManager().getDamageViews();
        this.criticalView = main.getViewManager().getCriticalView();
        this.criticalEnabled = this.criticalView != null;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof LivingEntity)) return;
        double damage = event.getFinalDamage();
        if (damage <= 0.01) return;
        EntityDamageEvent.DamageCause cause = event.getCause();
        IDamageDisplay viewDisplay = damageViews.get(cause);
        if (event instanceof EntityDamageByEntityEvent otherEvent) {
            if (criticalEnabled && otherEvent.isCritical()) {
                criticalView.spawn(otherEvent, damage);
                return;
            }
            if (otherEvent.getDamager() instanceof AreaEffectCloud) {
                viewDisplay = damageViews.get(EntityDamageEvent.DamageCause.MAGIC);
            }
            if (viewDisplay == null) return;
            viewDisplay.spawn(otherEvent, damage);
            return;
        }
        if (viewDisplay == null) return;
        viewDisplay.spawn(event, damage);
    }
}
