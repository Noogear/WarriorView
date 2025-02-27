package cn.warriorView.object.animation;

public record AnimationParams(
        float max,
        float baseSpeed,
        float maxSpeed,
        double angle,
        int moveCount,
        long interval
) {
}
