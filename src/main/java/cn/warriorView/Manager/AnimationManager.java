package cn.warriorView.Manager;

import cn.warriorView.Object.Animation.AnimationFactory;
import cn.warriorView.Object.Animation.AnimationParams;
import cn.warriorView.Object.Animation.IAnimation;
import cn.warriorView.Object.Animation.Type.Approach;
import cn.warriorView.Object.Animation.Type.Up;
import cn.warriorView.Util.FileUtil;
import cn.warriorView.Util.MathUtil;
import cn.warriorView.Util.XLogger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.*;


public class AnimationManager {

    private final Map<String, IAnimation> animationMap;

    public AnimationManager() {
        this.animationMap = new HashMap<>();
    }

    public void init() {
        animationMap.clear();
    }

    public List<IAnimation> getAnimation(List<String> names) {
        List<IAnimation> result = new ArrayList<>();
        if (names == null) return result;
        for (String name : names) {
            IAnimation anim = animationMap.get(name);
            if (anim != null) result.add(anim);
        }
        return result;
    }

    public List<IAnimation> getAnimation(List<String> primaryNames, List<String> fallbackNames) {
        List<String> safePrimary = (primaryNames != null) ? primaryNames : Collections.emptyList();
        List<String> safeFallback = (fallbackNames != null) ? fallbackNames : Collections.emptyList();
        List<IAnimation> primaryResult = getAnimation(safePrimary);
        if (!safePrimary.isEmpty() && !primaryResult.isEmpty()) {
            return primaryResult;
        }
        List<IAnimation> fallbackResult = getAnimation(safeFallback);
        if (!fallbackResult.isEmpty()) {
            return fallbackResult;
        }
        String errorMsg = String.format(
                "No animations found in both lists.%nPrimary list: %s%nFallback list: %s",
                safePrimary, safeFallback
        );
        throw new IllegalArgumentException(errorMsg);
    }

    public void load(YamlConfiguration yamlConfiguration) {
        Set<String> topKeys = yamlConfiguration.getKeys(false);
        for (String topKey : topKeys) {
            ConfigurationSection section = yamlConfiguration.getConfigurationSection(topKey);
            if (section == null) continue;

            List<IAnimation> animations = new LinkedList<>();
            for (ConfigurationSection animationSec : FileUtil.getSectionList(section, topKey)) {
                String type = animationSec.getString("type");
                if (type == null) continue;
                switch (type.replace("-", "").toLowerCase()) {
                    case "up":
                        animations.add(new Up(createAnimation(animationSec)));
                        break;
                    case "approach":
                        animations.add(new Approach(createAnimation(animationSec)));
                        break;
                }
            }
            animationMap.put(topKey, AnimationFactory.getGroup(animations));
        }
        XLogger.info("Successfully load " + animationMap.size() + " animation(s)");
    }

    public AnimationParams createAnimation(ConfigurationSection animationSec) {
        double max = animationSec.getDouble("max", -1);
        float[] speed = MathUtil.coverListToArray(animationSec.getFloatList("speed"), 2, 0);
        double angle = animationSec.getDouble("angle", 0);
        long initial = animationSec.getLong("interval", 2);
        byte moveCount = MathUtil.convertIntToByte(animationSec.getInt("move-count", 8));
        return new AnimationParams(
                MathUtil.round(max),
                MathUtil.round(speed[0]),
                MathUtil.round(speed[1]),
                angle,
                moveCount,
                initial
        );
    }

}
