package cn.warriorView;

import cn.warriorView.Manager.ConfigManager;
import cn.warriorView.Manager.ListenerManager;
import cn.warriorView.Manager.ViewManager;
import cn.warriorView.Util.Scheduler.XScheduler;
import cn.warriorView.Util.XLogger;
import com.github.retrooper.packetevents.PacketEvents;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import me.tofaa.entitylib.APIConfig;
import me.tofaa.entitylib.EntityLib;
import me.tofaa.entitylib.spigot.SpigotEntityLibPlatform;
import org.bukkit.plugin.java.JavaPlugin;

public final class Main extends JavaPlugin {
    private ViewManager viewManager;
    private ConfigManager configManager;
    private ListenerManager listenerManager;


    @Override
    public void onLoad() {
        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
        PacketEvents.getAPI().load();
    }

    @Override
    public void onEnable() {
        new XLogger(this);

        PacketEvents.getAPI().init();
        SpigotEntityLibPlatform platform = new SpigotEntityLibPlatform(this);
        APIConfig settings = new APIConfig(PacketEvents.getAPI());
        EntityLib.init(platform, settings);

        viewManager = new ViewManager();
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            new XScheduler(this, true);
        } catch (ClassNotFoundException e) {
            new XScheduler(this, false);
        }
        configManager = new ConfigManager(this);
        listenerManager = new ListenerManager(this);


    }

    @Override
    public void onDisable() {
        PacketEvents.getAPI().terminate();
        XScheduler.get().cancelTasks();
    }

    public ViewManager getViewManager() {
        return viewManager;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public ListenerManager getListenerManager() {
        return listenerManager;
    }

    public void reload() {
        configManager.load();
        listenerManager.load();
    }
}
