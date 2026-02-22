package cn.warriorview;

import cn.warriorview.api.WarriorView;
import cn.warriorview.api.WarriorViewAPI;
import cn.warriorview.api.manager.CommandManager;
import cn.warriorview.api.manager.ConfigManager;
import cn.warriorview.api.manager.FileManager;
import cn.warriorview.manager.FileManagerImpl;
import cn.warriorview.manager.script.BukkitScriptManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.plugin.java.JavaPlugin;

public class Main extends JavaPlugin implements WarriorView {

    private FileManager fileManager;
    private ConfigManager configManager;
    private CommandManager commandManager;
    private BukkitScriptManager scriptManager;

    @Override
    public void onEnable() {

        this.fileManager = new FileManagerImpl(this);

        // 初始化脚本生命周期管理器并装载
        this.scriptManager = new BukkitScriptManager(this);
        this.scriptManager.reloadScripts();

        WarriorViewAPI.register(this);

        getComponentLogger().info(
                Component.text("WarriorView v" + getVersion() + " 已启用", NamedTextColor.GREEN));
    }

    @Override
    public void onDisable() {
        if (scriptManager != null) {
            scriptManager.unloadScripts();
        }

        WarriorViewAPI.unregister();

        getComponentLogger().info(
                Component.text("WarriorView 已禁用", NamedTextColor.RED));
    }

    @Override
    public ConfigManager getConfigManager() {
        return configManager;
    }

    @Override
    public CommandManager getCommandManager() {
        return commandManager;
    }

    @Override
    public FileManager getFileManager() {
        return fileManager;
    }

    @Override
    public String getVersion() {
        return getPluginMeta().getVersion();
    }
}
