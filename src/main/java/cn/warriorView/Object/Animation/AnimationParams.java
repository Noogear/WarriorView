package cn.warriorView.Object.Animation;

import org.bukkit.util.Vector;

public record AnimationParams(
        Animation.type type,
        float max,
        float speed,
        Vector offset
) {
}
