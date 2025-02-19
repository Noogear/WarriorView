package cn.warriorView.Manager;

import cn.warriorView.Object.Animation.AnimationParams;
import cn.warriorView.Object.Animation.IAnimation;
import cn.warriorView.Object.Animation.Type.Side;
import cn.warriorView.Object.Animation.Type.Approach;
import cn.warriorView.Object.Animation.Type.ApproachAndSide;
import cn.warriorView.Util.MathUtil;
import cn.warriorView.Util.XLogger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class AnimationManager {

    private final Map<String, AnimationParams> upAnimations;
    private final Map<String, AnimationParams> sideAnimations;

    public AnimationManager() {
        this.upAnimations = new HashMap<>();
        this.sideAnimations = new HashMap<>();
    }

    public void init() {
        upAnimations.clear();
        sideAnimations.clear();
    }

    public void load(YamlConfiguration yamlConfiguration) {
        Set<String> topKeys = yamlConfiguration.getKeys(false);
        for (String topKey : topKeys) {
            ConfigurationSection section = yamlConfiguration.getConfigurationSection(topKey);
            if (section == null) continue;
            ConfigurationSection upSec = section.getConfigurationSection("up");
            if (upSec != null) {
                float max = MathUtil.round(upSec.getDouble("max", -1), 2);
                float offset = MathUtil.round(section.getDouble("offset", 0), 2);
                ConfigurationSection speedSec = upSec.getConfigurationSection("speed");
                float initial = 0;
                float accelerate = 0.1f;
                if (speedSec != null) {
                    initial = MathUtil.round(upSec.getDouble("initial", 0), 2);
                    accelerate = MathUtil.round(upSec.getDouble("accelerate", 0.1), 2);
                }
                upAnimations.put(topKey, new AnimationParams(max, initial, accelerate, offset));
            }
            ConfigurationSection sideSec = section.getConfigurationSection("side");
            if (sideSec != null) {
                float max = MathUtil.round(sideSec.getDouble("max", -1), 2);
                float offset = MathUtil.round(section.getDouble("offset", 0), 2);
                ConfigurationSection speedSec = sideSec.getConfigurationSection("speed");
                float initial = 0;
                float accelerate = 0;
                if (speedSec != null) {
                    initial = MathUtil.round(sideSec.getDouble("initial", 0), 2);
                    accelerate = MathUtil.round(sideSec.getDouble("accelerate", 0), 2);
                }
                sideAnimations.put(topKey, new AnimationParams(max, initial, accelerate, offset));
            }
        }
        XLogger.info("Successfully load " + MathUtil.parallelCount(upAnimations, sideAnimations) + " animation(s)");
    }

    public IAnimation get(String groupId, byte moveCount, long delay) {
        if (upAnimations.containsKey(groupId) && sideAnimations.containsKey(groupId)) {
            return new ApproachAndSide(upAnimations.get(groupId), sideAnimations.get(groupId), moveCount, delay);
        } else if (upAnimations.containsKey(groupId)) {
            return new Approach(upAnimations.get(groupId), moveCount, delay);
        } else if (sideAnimations.containsKey(groupId)) {
            return new Side(sideAnimations.get(groupId), moveCount, delay);
        } else {
            throw new RuntimeException("Animation " + groupId + " doesn't exist");
        }
    }


}
