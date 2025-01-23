package cn.warriorView.Manager;

import cn.warriorView.Animation.Animation;
import cn.warriorView.Animation.AnimationParams;
import cn.warriorView.Util.MathUtil;
import cn.warriorView.Util.XLogger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.util.Vector;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class AnimationManager {

    private final Map<String, AnimationParams> animations;

    public AnimationManager() {
        this.animations = new HashMap<>();
    }

    public void init() {
        animations.clear();
    }

    public void load(File file) {
        YamlConfiguration groups = YamlConfiguration.loadConfiguration(file);
        Set<String> topKeys = groups.getKeys(false);
        for (String topKey : topKeys) {
            ConfigurationSection section = groups.getConfigurationSection(topKey);
            if (section == null) continue;
            Animation.type type = Animation.type.valueOf(section.getString("type"));
            float max = MathUtil.round(section.getDouble("max"), 2);
            float speed = MathUtil.round(section.getDouble("speed"), 2);
            ConfigurationSection offsetSec = section.getConfigurationSection("offset");
            Vector offset = new Vector(0, 0, 0);
            if (offsetSec != null) {
                double offsetX = offsetSec.getDouble("x", 0);
                double offsetY = offsetSec.getDouble("y", 0);
                double offsetZ = offsetSec.getDouble("z", 0);
                offset = new Vector(offsetX, offsetY, offsetZ);
            }
            animations.put(topKey, new AnimationParams(type, max, speed, offset));
        }
        XLogger.info("Successfully load " + animations.size() + " animation(s)");
    }

    public AnimationParams getAnimation(String groupId) {
        return animations.get(groupId);
    }

}
