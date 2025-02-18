package cn.warriorView.Manager;

import cn.warriorView.Object.Replacement;
import cn.warriorView.Util.XLogger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class TextFormatManager {

    private final Map<String, Replacement> replacements;

    public TextFormatManager() {
        this.replacements = new HashMap<>();
    }

    public void init() {
        replacements.clear();
    }

    public void load(YamlConfiguration yamlConfiguration) {
        Set<String> topKeys = yamlConfiguration.getKeys(false);
        for (String topKey : topKeys) {
            ConfigurationSection section = yamlConfiguration.getConfigurationSection(topKey);

            String[] numbers = new String[0];
            Map<String,String> chars = new HashMap<>();
            Map<Integer,String> quantize = new HashMap<>();

            if (section == null) continue;
            ConfigurationSection numberSection = section.getConfigurationSection("number");
            if (numberSection != null) {
                ConfigurationSection quantizeSection = numberSection.getConfigurationSection("quantize");
                if (quantizeSection != null) {
                    for (String key : quantizeSection.getKeys(false)) {
                        quantize.put(Integer.parseInt(key), quantizeSection.getString(key));
                    }
                }
                numbers = numberSection.getStringList("replace").toArray(new String[0]);

            }
            ConfigurationSection charSection = section.getConfigurationSection("char");

            if (charSection != null) {
                for (String key : charSection.getKeys(false)) {
                    chars.put(key, charSection.getString(key));
                }
            }

            replacements.put(topKey, Replacement.create(numbers, chars));
        }
        XLogger.info("Successfully load " + replacements.size() + " replacement(s)");
    }

    public Replacement getReplacement(String groupId) {
        return replacements.get(groupId);
    }

}
