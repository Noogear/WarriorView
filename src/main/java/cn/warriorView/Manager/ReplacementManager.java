package cn.warriorView.Manager;

import cn.warriorView.Object.Replace;
import cn.warriorView.Util.XLogger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ReplacementManager {

    private final Map<String, Replace> replacements;

    public ReplacementManager() {
        this.replacements = new HashMap<>();
    }

    public void init() {
        replacements.clear();
    }

    public void load(YamlConfiguration yamlConfiguration) {
        Set<String> topKeys = yamlConfiguration.getKeys(false);
        for (String topKey : topKeys) {
            ConfigurationSection section = yamlConfiguration.getConfigurationSection(topKey);
            if (section == null) continue;
            String[] numbers = section.getStringList("number").toArray(new String[0]);
            List<String> chars = section.getStringList("char");
            replacements.put(topKey, Replace.create(numbers, chars));
        }
        XLogger.info("Successfully load " + replacements.size() + " replacement(s)");
    }

    public Replace getReplacement(String groupId) {
        return replacements.get(groupId);
    }

}
