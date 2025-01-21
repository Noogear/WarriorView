package cn.warriorView.Listener;

import cn.warriorView.Main;
import cn.warriorView.View.RegainView.RegainView;
import cn.warriorView.View.ViewDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityRegainHealthEvent;

public class RegainHealth implements Listener {
    private final Main plugin;

    public RegainHealth(Main main) {
        this.plugin = main;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onRegainHealth(EntityRegainHealthEvent event) {
        if (!(event.getEntity() instanceof LivingEntity)) return;
        EntityRegainHealthEvent.RegainReason reason = event.getRegainReason();
        ViewDisplay viewDisplay = plugin.getViewManager().getRegainViews().get(reason);
        if(viewDisplay instanceof RegainView regainView){
            regainView.spawn(event);
        }
    }


}
