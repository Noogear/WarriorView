package cn.warriorView.listener;

import cn.warriorView.Main;
import cn.warriorView.view.category.regain.RegainView;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityRegainHealthEvent;

import java.util.EnumMap;

public class RegainHealth implements Listener {
    private final EnumMap<EntityRegainHealthEvent.RegainReason, RegainView> regainViews;

    public RegainHealth(Main main) {
        this.regainViews = main.getViewManager().getRegainViews();
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onRegainHealth(EntityRegainHealthEvent event) {
        if (!(event.getEntity() instanceof LivingEntity)) return;
        EntityRegainHealthEvent.RegainReason reason = event.getRegainReason();
        RegainView viewDisplay = regainViews.get(reason);
        if (viewDisplay == null) return;
        viewDisplay.spawn(event);
    }

}
