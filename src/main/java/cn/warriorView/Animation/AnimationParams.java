package cn.warriorView.Animation;

import org.bukkit.util.Vector;

public record AnimationParams(
        Animation.type type,
        float max,
        float speed,
        Vector offset
) {
}
