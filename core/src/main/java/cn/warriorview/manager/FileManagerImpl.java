package cn.warriorview.manager;

import cn.warriorview.api.manager.FileManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.slf4j.Logger;

public class FileManagerImpl implements FileManager {
    private final JavaPlugin plugin;
    private final Logger logger;

    public FileManagerImpl(JavaPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getComponentLogger();
    }

}
