package cn.warriorView.Listener;

import cn.warriorView.Main;
import cn.warriorView.View.DamageOtherView;
import cn.warriorView.View.DamageView;
import cn.warriorView.View.ProjectileView;
import cn.warriorView.View.ViewDisplay;
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
        EntityDamageEvent.DamageCause cause = event.getCause();
        ViewDisplay viewDisplay = plugin.getViewManager().getDamageViews().get(cause);

        if (viewDisplay instanceof DamageOtherView damageOtherView) {
            if (event instanceof EntityDamageByEntityEvent damageOtherEvent) {
                if (damageOtherView instanceof ProjectileView projectileView) {
                    projectileView.spawn(damageOtherEvent);
                    return;
                }
                damageOtherView.spawn(damageOtherEvent);

            }
        } else if (viewDisplay instanceof DamageView damageView) {


        }
    }


}
