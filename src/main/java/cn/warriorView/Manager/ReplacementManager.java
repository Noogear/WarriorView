package cn.warriorView.Manager;

import cn.warriorView.Object.Replacement;
import cn.warriorView.Util.XLogger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ReplacementManager {

    private final Map<String, Replacement> replacements;

    public ReplacementManager() {
        this.replacements = new HashMap<>();
    }

    public void init() {
        replacements.clear();
    }

    public void load(File file) {
        YamlConfiguration groups = YamlConfiguration.loadConfiguration(file);
        Set<String> topKeys = groups.getKeys(false);
        for (String topKey : topKeys) {
            ConfigurationSection section = groups.getConfigurationSection(topKey);
            if (section == null) continue;
            String[] numbers = section.getStringList("numbers").toArray(new String[0]);
            List<String> chars = section.getStringList("chars");
            replacements.put(topKey, new Replacement(numbers, chars));
        }
        XLogger.info("Successfully load " + replacements.size() + " replacement(s)");
    }

    public Replacement getReplacement(String groupId) {
        return replacements.get(groupId);
    }

}
