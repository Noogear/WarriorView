package cn.warriorView;

import cn.warriorView.Listener.EntityDamageOther;
import cn.warriorView.Manager.ViewManager;
import cn.warriorView.Util.XLogger;
import com.github.retrooper.packetevents.PacketEvents;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import me.tofaa.entitylib.APIConfig;
import me.tofaa.entitylib.EntityLib;
import me.tofaa.entitylib.spigot.SpigotEntityLibPlatform;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class Main extends JavaPlugin {
    private ViewManager viewManager;

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
        Bukkit.getServer().getPluginManager().registerEvents(new EntityDamageOther(this), this);

    }

    @Override
    public void onDisable() {
        PacketEvents.getAPI().terminate();
    }

    public ViewManager getViewManager() {
        return viewManager;
    }
}
