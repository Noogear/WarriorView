package cn.warriorView.Listener;

import cn.warriorView.Main;
import cn.warriorView.View.DamageView.CriticalView;
import cn.warriorView.View.DamageView.DamageOtherView;
import cn.warriorView.View.DamageView.DamageView;
import cn.warriorView.View.DamageView.ProjectileView;
import cn.warriorView.View.ViewDisplay;
import org.bukkit.entity.AreaEffectCloud;
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
//        XLogger.info(event.getEntity().getName() + " " + event.getCause() + " " + event.getFinalDamage() + " " + damageViews.get(event.getCause()));
        if (!(event.getEntity() instanceof LivingEntity)) return;

        double damage = event.getFinalDamage();
        if (damage <= 0.01) return;

        EntityDamageEvent.DamageCause cause = event.getCause();
        ViewDisplay viewDisplay = damageViews.get(cause);

        if (viewDisplay == null) return;

        if (viewDisplay instanceof DamageOtherView damageOtherView) {
            EntityDamageByEntityEvent damageOtherEvent = (EntityDamageByEntityEvent) event;
//            XLogger.info(String.valueOf(damageOtherEvent.getDamager().getType()));
            if (damageOtherEvent.isCritical()) {
                criticalView.spawn(damageOtherEvent, damage);
                return;
            }

            if (damageOtherEvent.getDamager() instanceof LivingEntity) {
                damageOtherView.spawn(damageOtherEvent, damage);
                return;
            }

            if (damageOtherView instanceof ProjectileView projectileView) {
                projectileView.spawn(damageOtherEvent, damage);
                return;
            }

            if (damageOtherEvent.getDamager() instanceof AreaEffectCloud) {
                viewDisplay = damageViews.get(EntityDamageEvent.DamageCause.MAGIC);
            } else {
                viewDisplay = damageViews.get(EntityDamageEvent.DamageCause.CUSTOM);
            }
            if (viewDisplay == null) return;
            ((DamageView) viewDisplay).spawn(event, damage);

        } else {

            ((DamageView) viewDisplay).spawn(event, damage);

        }
    }

}
