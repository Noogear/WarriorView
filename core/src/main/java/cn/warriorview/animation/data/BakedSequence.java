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
) {}
