package cn.warriorView.Listener;

import cn.warriorView.Main;
import cn.warriorView.View.DamageView.DamageOtherView;
import cn.warriorView.View.DamageView.DamageView;
import cn.warriorView.View.DamageView.ProjectileView;
import cn.warriorView.View.ViewDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;

public class EntityDamage implements Listener {

    private final Main plugin;

    public EntityDamage(Main main) {
        this.plugin = main;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof LivingEntity)) return;
        double damage = event.getFinalDamage();
        if (damage <= 0.01) return;
        EntityDamageEvent.DamageCause cause = event.getCause();
        ViewDisplay viewDisplay = plugin.getViewManager().getDamageViews().get(cause);
        if (viewDisplay == null) return;
        if (viewDisplay instanceof DamageOtherView damageOtherView) {
            if (event instanceof EntityDamageByEntityEvent damageOtherEvent) {
                if (damageOtherEvent.isCritical()) {
                    plugin.getViewManager().getCriticalView().spawn(damageOtherEvent, damage);
                    return;
                }
                if (damageOtherView instanceof ProjectileView projectileView) {
                    projectileView.spawn(damageOtherEvent, damage);
                    return;
                }
                damageOtherView.spawn(damageOtherEvent, damage);
            }
        } else if (viewDisplay instanceof DamageView damageView) {
            damageView.spawn(event, damage);
        }

    }
}
