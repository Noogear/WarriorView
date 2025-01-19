package cn.warriorView;

import cn.warriorView.Listener.EntityDamageOther;
import com.github.retrooper.packetevents.PacketEvents;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import me.tofaa.entitylib.APIConfig;
import me.tofaa.entitylib.EntityLib;
import me.tofaa.entitylib.spigot.SpigotEntityLibPlatform;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class Main extends JavaPlugin {

    @Override
    public void onLoad() {
        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
        PacketEvents.getAPI().load();
    }

    @Override
    public void onEnable() {
        // Plugin startup logic
        PacketEvents.getAPI().init();
        SpigotEntityLibPlatform platform = new SpigotEntityLibPlatform(this);
        APIConfig settings = new APIConfig(PacketEvents.getAPI());
        EntityLib.init(platform, settings);
        Bukkit.getServer().getPluginManager().registerEvents(new EntityDamageOther(this),this);

    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
        PacketEvents.getAPI().terminate();
    }
}
