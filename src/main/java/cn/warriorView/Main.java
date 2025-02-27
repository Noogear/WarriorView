package cn.warriorView;

import cn.warriorView.configuration.file.Language;
import cn.warriorView.manager.ConfigManager;
import cn.warriorView.manager.ListenerManager;
import cn.warriorView.manager.ViewManager;
import cn.warriorView.object.animation.AnimationTask;
import cn.warriorView.util.MsgUtil;
import cn.warriorView.util.XLogger;
import cn.warriorView.util.scheduler.XRunnable;
import cn.warriorView.util.scheduler.XScheduler;
import com.github.retrooper.packetevents.PacketEvents;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

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
        long startTime = System.currentTimeMillis();
        new XLogger(this);

        PacketEvents.getAPI().init();

        viewManager = new ViewManager();
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            new XScheduler(this, true);
        } catch (ClassNotFoundException e) {
            new XScheduler(this, false);
        }
        configManager = new ConfigManager(this);
        listenerManager = new ListenerManager(this);
        PluginCommand mainCommand = getCommand("warriorview");
        if (mainCommand != null) {
            mainCommand.setExecutor(new Commands(this));
        } else {
            XLogger.err("Failed to load command.");
        }

        long elapsedTime = System.currentTimeMillis() - startTime;
        XLogger.info("Plugin loaded successfully in " + elapsedTime + " ms");
    }

    @Override
    public void onDisable() {
        PacketEvents.getAPI().terminate();
        XScheduler.get().cancelTasks();
        configManager.init();
        HandlerList.unregisterAll(this);
        AnimationTask.getInstance().init();
    }

    public ViewManager getViewManager() {
        return viewManager;
    }

    public void reload(@NotNull CommandSender sender) {
        new XRunnable() {
            @Override
            public void run() {
                long startTime = System.currentTimeMillis();
                configManager.load();
                listenerManager.load();
                long elapsedTime = System.currentTimeMillis() - startTime;
                MsgUtil.send(sender, Language.reload, elapsedTime);
            }
        }.async();

    }
}
