package cn.warriorView.Manager;

import cn.warriorView.Configuration.File.Config;
import cn.warriorView.Listener.EntityDamage;
import cn.warriorView.Listener.RegainHealth;
import cn.warriorView.Main;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.PluginManager;

public class ListenerManager {
    private final Main plugin;
    private final PluginManager pluginManager;

    public ListenerManager(Main main) {
        this.plugin = main;
        pluginManager = plugin.getServer().getPluginManager();
        load();
    }

    public void load() {
        HandlerList.unregisterAll(plugin);

        if (!Config.enabled) return;

        if (Config.damageEntity.enabled) {
            pluginManager.registerEvents(new EntityDamage(plugin), plugin);
        }

        if (Config.regainHealth.enabled) {
            pluginManager.registerEvents(new RegainHealth(plugin), plugin);
        }

    }

}
