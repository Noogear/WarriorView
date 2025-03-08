package cn.warriorView.object.animation;

import cn.warriorView.util.XLogger;

public record AnimationParams(
        float max,
        float baseSpeed,
        float acceleration,
        double angle,
        int moveCount,
        long interval
) {
    public AnimationParams {
        if (interval < 1) {
            interval = 1;
            XLogger.err("Animation interval must be greater than 1");
        }
        if(moveCount < 0) {
            moveCount = 0;
            XLogger.err("Animation moveCount must be greater than 0");
        }
    }
}
