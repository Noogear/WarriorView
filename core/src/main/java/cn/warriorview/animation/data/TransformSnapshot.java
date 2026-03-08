package cn.warriorview.animation.data;

/**
 * An immutable snapshot of the full transform state for a single rendered frame.
 * All values are pre-computed and stored as primitives to support zero-GC replay.
 * <p>
 * Coordinate conventions:
 * <ul>
 *   <li>translation: world-relative offset in blocks from the spawn anchor point</li>
 *   <li>scale: multiplier (1.0 = default size)</li>
 *   <li>left_rotation / right_rotation: quaternion components (x, y, z, w)</li>
 *   <li>textOpacity: -1 to 127; -1 means "use default"</li>
 * </ul>
 * <p>
 * Being a {@code record}, {@link #equals} and {@link #hashCode} are auto-generated
 * by the compiler and compare all 15 primitive fields with {@code ==} semantics.
 * This is intentionally relied on by {@link cn.warriorview.animation.parse.KeyframeParser}
 * for zero-delta frame deduplication at load time.
 */
public record TransformSnapshot(
        float tx, float ty, float tz,          // translation
        float sx, float sy, float sz,          // scale
        float lrx, float lry, float lrz, float lrw, // left rotation quaternion
        float rrx, float rry, float rrz, float rrw, // right rotation quaternion
        byte  textOpacity                       // -1 = default (127)
) {
    /** A do-nothing identity transform that matches the display entity default state. */
    public static final TransformSnapshot IDENTITY = new TransformSnapshot(
            0f, 0f, 0f,          // translation
            1f, 1f, 1f,          // scale
            0f, 0f, 0f, 1f,      // left rotation (identity quaternion)
            0f, 0f, 0f, 1f,      // right rotation (identity quaternion)
            (byte) -1            // opacity: default
    );
}
