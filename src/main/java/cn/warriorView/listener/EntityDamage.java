package cn.warriorView.listener;

import cn.warriorView.Main;
import cn.warriorView.configuration.file.Config;
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
    private final double ignoreValue;

    public EntityDamage(Main main) {
        this.damageViews = main.getViewManager().getDamageViews();
        this.criticalView = main.getViewManager().getCriticalView();
        this.criticalEnabled = this.criticalView != null;
        this.ignoreValue = Config.damageEntity.ignoreValue;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof LivingEntity)) return;
        double value = event.getFinalDamage();
        if (value <= ignoreValue) return;
        EntityDamageEvent.DamageCause cause = event.getCause();
        IDamageDisplay viewDisplay = damageViews.get(cause);
        if (event instanceof EntityDamageByEntityEvent otherEvent) {
            if (otherEvent.getDamager().getWorld() != event.getEntity().getWorld()) return;
            if (criticalEnabled && otherEvent.isCritical()) {
                criticalView.spawn(otherEvent, value);
                return;
            }
            if (otherEvent.getDamager() instanceof AreaEffectCloud) {
                viewDisplay = damageViews.get(EntityDamageEvent.DamageCause.MAGIC);
            }
            if (viewDisplay == null) return;
            viewDisplay.spawn(otherEvent, value);
            return;
        }
        if (viewDisplay == null) return;
        viewDisplay.spawn(event, value);
    }
}
