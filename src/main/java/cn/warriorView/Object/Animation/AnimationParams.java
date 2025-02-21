package cn.warriorView.Object.Animation;

public record AnimationParams(
        float max,
        float baseSpeed,
        float maxSpeed,
        double angle,
        int moveCount,
        long interval
) {
}
