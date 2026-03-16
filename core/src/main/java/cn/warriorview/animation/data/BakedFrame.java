package cn.warriorview.animation.data;

/**
 * A single pre-baked animation frame, ready to be sent as a packet without
 * any runtime computation.
 *
 * <p>All instances are immutable records and are safe to share across threads.</p>
 *
 * @param tickOffset           ticks from the start of the animation when this frame fires
 * @param interpolationDelay   value written to MC metadata index 8 (interpolation_delay)
 * @param interpolationTicks   value written to MC metadata index 9 (transform_duration)
 * @param snapshot             full transform/display state at this frame
 */
public record BakedFrame(
        int              tickOffset,
        int              interpolationDelay,
        int              interpolationTicks,
        TransformSnapshot snapshot
) {
    /**
     * Ticks for the sentinel "hold" frame appended at the end of every animation.
     * The sentinel duplicates the last real frame's transform to keep the MC client's
     * interpolation system active, preventing {@code text_opacity} from resetting to
     * default (fully opaque) before the destroy packet arrives.
     */
    public static final int SENTINEL_HOLD_TICKS = 2;
}
