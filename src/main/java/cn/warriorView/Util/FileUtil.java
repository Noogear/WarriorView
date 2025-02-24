package cn.warriorView.Util;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class FileUtil {

    @NotNull
    public static List<ConfigurationSection> getSectionList(ConfigurationSection parent, String key) {
        List<ConfigurationSection> list = new ArrayList<>();
        List<Map<?, ?>> rawList = parent.getMapList(key);
        for (Map<?, ?> map : rawList) {
            MemoryConfiguration section = new MemoryConfiguration();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String sectionKey = entry.getKey().toString();
                section.set(sectionKey, processValue(section, sectionKey, entry.getValue()));
            }
            list.add(section);
        }
        return list;
    }

    private static Object processValue(ConfigurationSection parent, String key, Object value) {
        if (value instanceof Map<?, ?> map) {
            ConfigurationSection section;
            if (parent == null || key == null) {
                section = new MemoryConfiguration();
            } else {
                section = parent.createSection(key);
            }
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String mapKey = entry.getKey().toString();
                section.set(mapKey, processValue(section, mapKey, entry.getValue()));
            }
            return section;
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>();
            for (Object object : list) {
                result.add(processValue(null, null, object));
            }
            return result;
        }
        return value;
    }
}
