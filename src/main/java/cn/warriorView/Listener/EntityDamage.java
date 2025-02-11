package cn.warriorView.Listener;

import cn.warriorView.Main;
import cn.warriorView.View.DamageView.CriticalView;
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

import java.util.Map;

public class EntityDamage implements Listener {
    private final Map<EntityDamageEvent.DamageCause, ViewDisplay> damageViews;
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
        ViewDisplay viewDisplay = damageViews.get(cause);

        if (viewDisplay == null) return;

        if (viewDisplay instanceof DamageOtherView damageOtherView) {
            handleOtherDamage((EntityDamageByEntityEvent) event, damageOtherView, damage);
        } else if (viewDisplay instanceof DamageView damageView) {
            damageView.spawn(event, damage);
        }
    }

    private void handleOtherDamage(EntityDamageByEntityEvent event, DamageOtherView viewDisplay, double damage) {
        if (event.isCritical()) {
            criticalView.spawn(event, damage);
            return;
        }
        if (viewDisplay instanceof ProjectileView projectileView) {
            projectileView.spawn(event, damage);
        } else {
            viewDisplay.spawn(event, damage);
        }
    }

}
