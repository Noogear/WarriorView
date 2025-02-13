package cn.warriorView.Listener;

import cn.warriorView.Main;
import cn.warriorView.View.Category.Damage.CriticalView;
import cn.warriorView.View.Category.Damage.IDamageDisplay;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;

import java.util.Map;

public class EntityDamage implements Listener {
    private final Map<EntityDamageEvent.DamageCause, IDamageDisplay> damageViews;
    private final CriticalView criticalView;

    public EntityDamage(Main main) {
        this.damageViews = main.getViewManager().getDamageViews();
        this.criticalView = main.getViewManager().getCriticalView();
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof LivingEntity)) return;
        double damage = event.getFinalDamage();
        if (damage <= 0.01) return;
        EntityDamageEvent.DamageCause cause = event.getCause();
        IDamageDisplay viewDisplay = damageViews.get(cause);
        if (event instanceof EntityDamageByEntityEvent otherEvent) {
            if (criticalView != null) {
                if (otherEvent.isCritical()) {
                    criticalView.spawn(otherEvent, damage);
                    return;
                }
            }
            if (otherEvent.getDamager() instanceof AreaEffectCloud) {
                viewDisplay = damageViews.get(EntityDamageEvent.DamageCause.MAGIC);
            }
        }
        if (viewDisplay == null) return;
        viewDisplay.spawn(event, damage);
    }
}
