package cn.warriorView.Manager;

import cn.warriorView.Object.Animation.AnimationGroup;
import cn.warriorView.Object.Animation.AnimationParams;
import cn.warriorView.Object.Animation.IAnimation;
import cn.warriorView.Object.Animation.Type.Approach;
import cn.warriorView.Object.Animation.Type.Up;
import cn.warriorView.Object.Animation.Type.UpAndApproach;
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

    public IAnimation getAnimation(String name) {
        return animationMap.get(name);
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
                    case "upandapproach":
                        List<AnimationParams> paramsList = createCompoundAnimation(animationSec);
                        animations.add(new UpAndApproach(paramsList.get(0), paramsList.get(1)));
                        break;
                }
            }
            animationMap.put(topKey, AnimationGroup.create(animations));
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

    public List<AnimationParams> createCompoundAnimation(ConfigurationSection animationSec) {
        double[] max = MathUtil.coverListToArray(animationSec.getDoubleList("max"), 2, -1);
        double[][] speeds = loadSpeedRanges(animationSec);
        double[] angle = MathUtil.coverListToArray(animationSec.getDoubleList("angle"), 2, 0);
        long[] interval = MathUtil.coverListToArray(animationSec.getLongList("interval"), 2, 2);
        int[] moveCount = MathUtil.coverListToArray(animationSec.getIntegerList("move-count"), 2, 4);

        AnimationParams params1 = new AnimationParams(
                MathUtil.round(max[0]),
                MathUtil.round(speeds[0][0]),
                MathUtil.round(speeds[0][1]),
                angle[0],
                MathUtil.convertIntToByte(moveCount[0]),
                interval[0]
        );

        AnimationParams params2 = new AnimationParams(
                MathUtil.round(max[1]),
                MathUtil.round(speeds[1][0]),
                MathUtil.round(speeds[1][1]),
                angle[1],
                MathUtil.convertIntToByte(moveCount[1]),
                interval[1]
        );

        return Arrays.asList(params1, params2);
    }

    public double[][] loadSpeedRanges(ConfigurationSection config) {
        List<?> speedList = config.getList("speed");
        double[][] result = new double[2][2];
        Arrays.fill(result[0], 0.0);
        Arrays.fill(result[1], 0.0);

        if (speedList == null || speedList.isEmpty()) return result;

        for (int phase = 0; phase < 2 && phase < speedList.size(); phase++) {
            Object entry = speedList.get(phase);
            if (entry instanceof List<?> pair) {
                result[phase][0] = parseDouble(!pair.isEmpty() ? pair.get(0) : 0.0);
                result[phase][1] = parseDouble(pair.size() > 1 ? pair.get(1) : result[phase][0]);
            } else if (entry instanceof Number) {
                result[phase][0] = ((Number) entry).doubleValue();
                result[phase][1] = result[phase][0];
            }
        }
        return result;
    }

    private double parseDouble(Object obj) {
        try {
            return Double.parseDouble(obj.toString());
        } catch (Exception e) {
            return 0.0;
        }
    }

}
