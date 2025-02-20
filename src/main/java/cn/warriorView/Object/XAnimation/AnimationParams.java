package cn.warriorView.Object.XAnimation;

public record AnimationParams(
        float max,
        float baseSpeed,
        float maxSpeed,
        double angle,
        int moveCount,
        long interval
) {
}
