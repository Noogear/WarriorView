package cn.warriorview.animation.data;

/**
 * A fully pre-baked, immutable animation sequence.
 *
 * <p>For <em>keyframe</em> animations, {@code frames} is built once at plugin load time
 * and shared by reference across all concurrent playback instances.</p>
 *
 * <p>For <em>equation</em> animations, a new {@code BakedSequence} is built once at
 * spawn time per instance (using the instance's random value {@code r}), then
 * discarded after all frames have been sent.  The {@code BakedFrame[]} array is
 * pre-sized to {@code ceil(totalTicks / sampleInterval)} and allocated only once.</p>
 *
 * @param frames      ordered array of pre-baked frames sorted by {@code tickOffset}
 * @param totalTicks  total duration of the animation in game ticks
 * @param settings    shared display settings that apply for the whole animation
 */
public record BakedSequence(
        BakedFrame[]    frames,
        int             totalTicks,
        DisplaySettings settings
) {

    /**
     * 创建一个新的 BakedSequence，其中每帧的 tx/tz 被绕 Y 轴旋转。
     * 用于将视角空间的帧数据转换为世界空间。
     *
     * @param cos cos(yawRad)
     * @param sin sin(yawRad)
     * @return 旋转后的新序列（不修改原始帧）
     */
    public BakedSequence rotateXZ(float cos, float sin) {
        BakedFrame[] rotated = new BakedFrame[frames.length];
        for (int i = 0; i < frames.length; i++) {
            BakedFrame f = frames[i];
            TransformSnapshot s = f.snapshot();
            float tx =  s.tx() * cos + s.tz() * sin;
            float tz = -s.tx() * sin + s.tz() * cos;
            rotated[i] = new BakedFrame(
                    f.tickOffset(),
                    f.interpolationDelay(),
                    f.interpolationTicks(),
                    new TransformSnapshot(
                            tx, s.ty(), tz,
                            s.sx(), s.sy(), s.sz(),
                            s.lrx(), s.lry(), s.lrz(), s.lrw(),
                            s.rrx(), s.rry(), s.rrz(), s.rrw(),
                            s.textOpacity()
                    )
            );
        }
        return new BakedSequence(rotated, totalTicks, settings);
    }
}
