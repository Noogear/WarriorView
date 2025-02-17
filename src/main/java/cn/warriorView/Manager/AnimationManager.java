package cn.warriorView.Manager;

import cn.warriorView.Object.Animation.AnimationParams;
import cn.warriorView.Util.MathUtil;
import cn.warriorView.Util.XLogger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.util.Vector;

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

    public void load(YamlConfiguration yamlConfiguration) {
        Set<String> topKeys = yamlConfiguration.getKeys(false);
        for (String topKey : topKeys) {
            ConfigurationSection section = yamlConfiguration.getConfigurationSection(topKey);
            if (section == null) continue;
            float max = MathUtil.round(section.getDouble("max"), 2);
            ConfigurationSection speedSec = section.getConfigurationSection("speed");
            float initial = 0;
            float acceleration = 0.1f;
            if (speedSec != null) {
                initial = MathUtil.round(speedSec.getDouble("initial"), 2);
                acceleration = MathUtil.round(speedSec.getDouble("acceleration"), 2);
            } else{
                throw new RuntimeException("Animation " + topKey + " has no speed section");
            }
            ConfigurationSection offsetSec = section.getConfigurationSection("offset");
            Vector offset = new Vector(0, 0, 0);
            if (offsetSec != null) {
                double offsetX = offsetSec.getDouble("x", 0);
                double offsetY = offsetSec.getDouble("y", 0);
                double offsetZ = offsetSec.getDouble("z", 0);
                offset = new Vector(offsetX, offsetY, offsetZ);
            }
            animations.put(topKey, new AnimationParams(max, initial, acceleration, offset));
        }
        XLogger.info("Successfully load " + animations.size() + " animation(s)");
    }

    public AnimationParams getAnimation(String groupId) {
        return animations.get(groupId);
    }

}
