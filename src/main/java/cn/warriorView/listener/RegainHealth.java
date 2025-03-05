package cn.warriorView.listener;

import cn.warriorView.Main;
import cn.warriorView.configuration.file.Config;
import cn.warriorView.view.category.regain.RegainView;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityRegainHealthEvent;

import java.util.EnumMap;

public class RegainHealth implements Listener {
    private final EnumMap<EntityRegainHealthEvent.RegainReason, RegainView> regainViews;
    private final double ignoreValue;

    public RegainHealth(Main main) {
        this.regainViews = main.getViewManager().getRegainViews();
        this.ignoreValue = Config.regainHealth.ignoreValue;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onRegainHealth(EntityRegainHealthEvent event) {
        if (!(event.getEntity() instanceof LivingEntity)) return;
        double value = event.getAmount();
        if (value <= ignoreValue) return;
        EntityRegainHealthEvent.RegainReason reason = event.getRegainReason();
        RegainView viewDisplay = regainViews.get(reason);
        if (viewDisplay == null) return;
        viewDisplay.spawn(event, value);
    }

}
