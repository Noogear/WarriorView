package cn.warriorView.Object.Animation;

import org.bukkit.util.Vector;

public record AnimationParams(
        float max,
        float initial,
        float acceleration,
        Vector offset
) {
}
