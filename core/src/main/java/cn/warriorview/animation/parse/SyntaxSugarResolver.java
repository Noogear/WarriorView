package cn.warriorview.animation.parse;

import cn.warriorview.animation.data.TransformSnapshot;
import org.bukkit.configuration.ConfigurationSection;

import java.util.List;

/**
 * Translates the user-friendly "syntax sugar" fields in a keyframe entry
 * ({@code move}, {@code size}, {@code tilt}, {@code spin}, {@code opacity})
 * into a raw {@link TransformSnapshot}, starting from a given base snapshot.
 *
 * <p>This class contains only static, pure utility methods. All computation is
 * performed at load time, so there is zero runtime overhead during playback.</p>
 *
 * <h3>Syntax summary</h3>
 * <pre>
 * move:
 *   up: 0.5
 *   right: 0.1
 * size: 1.5          ← uniform scale
 * size:
 *   width:  2.0      ← non-uniform scale (x, z)
 *   height: 1.0
 * tilt:
 *   left: 15         ← degrees, tilt around Z axis
 * spin:
 *   left: 90         ← degrees, rotate around Y axis
 * opacity: 50%
 * </pre>
 */
public final class SyntaxSugarResolver {

    private SyntaxSugarResolver() {}

    // --- Delta tracking indices for +/- and % easing modifiers ---
    static final int D_MOVE_UP = 0, D_MOVE_DOWN = 1, D_MOVE_RIGHT = 2, D_MOVE_LEFT = 3,
                     D_MOVE_FWD = 4, D_MOVE_BWD = 5,
                     D_TILT_LEFT = 6, D_TILT_RIGHT = 7,
                     D_SPIN_LEFT = 8, D_SPIN_RIGHT = 9,
                     D_SIZE = 10, D_SIZE_H = 11,
                     D_OPACITY = 12;
    static final int DELTA_COUNT = 13;

    /**
     * Resolves a keyframe YAML section into a {@link TransformSnapshot} by merging
     * sugar fields and raw-NMS fields on top of {@code base}.
     *
     * @param base    the previous frame's snapshot (used for incremental move offsets)
     * @param section the YAML section for this keyframe entry
     * @return the fully-resolved {@link TransformSnapshot} for this frame
     */
    public static TransformSnapshot resolve(TransformSnapshot base, ConfigurationSection section) {
        return resolve(base, section, new float[DELTA_COUNT]);
    }

    /**
     * Resolves a keyframe YAML section into a {@link TransformSnapshot}, tracking
     * per-property deltas for {@code +N}, {@code -N}, {@code +N%}, {@code -N%} modifiers.
     *
     * @param base    the previous frame's snapshot
     * @param section the YAML section for this keyframe entry
     * @param deltas  mutable delta array carried across frames (length = {@link #DELTA_COUNT})
     * @return the fully-resolved {@link TransformSnapshot} for this frame
     */
    public static TransformSnapshot resolve(TransformSnapshot base, ConfigurationSection section,
                                             float[] deltas) {
        // Start from the base
        float tx = base.tx(), ty = base.ty(), tz = base.tz();
        float sx = base.sx(), sy = base.sy(), sz = base.sz();
        float lrx = base.lrx(), lry = base.lry(), lrz = base.lrz(), lrw = base.lrw();
        float rrx = base.rrx(), rry = base.rry(), rrz = base.rrz(), rrw = base.rrw();
        byte opacity = base.textOpacity();

        // --- Raw NMS fields (take priority over syntax sugar) ---
        if (section.contains("translation")) {
            List<?> v = section.getList("translation");
            if (v != null && v.size() >= 3) {
                tx = toFloat(v.get(0));
                ty = toFloat(v.get(1));
                tz = toFloat(v.get(2));
            }
        }
        if (section.contains("scale")) {
            List<?> v = section.getList("scale");
            if (v != null && v.size() >= 3) {
                sx = toFloat(v.get(0));
                sy = toFloat(v.get(1));
                sz = toFloat(v.get(2));
            }
        }
        if (section.contains("left_rotation")) {
            List<?> v = section.getList("left_rotation");
            if (v != null && v.size() >= 4) {
                lrx = toFloat(v.get(0)); lry = toFloat(v.get(1));
                lrz = toFloat(v.get(2)); lrw = toFloat(v.get(3));
            } else if (v != null && v.size() == 3) {
                float[] q = eulerToQuaternion(toFloat(v.get(0)), toFloat(v.get(1)), toFloat(v.get(2)));
                lrx = q[0]; lry = q[1]; lrz = q[2]; lrw = q[3];
            }
        }
        if (section.contains("right_rotation")) {
            List<?> v = section.getList("right_rotation");
            if (v != null && v.size() >= 4) {
                rrx = toFloat(v.get(0)); rry = toFloat(v.get(1));
                rrz = toFloat(v.get(2)); rrw = toFloat(v.get(3));
            } else if (v != null && v.size() == 3) {
                float[] q = eulerToQuaternion(toFloat(v.get(0)), toFloat(v.get(1)), toFloat(v.get(2)));
                rrx = q[0]; rry = q[1]; rrz = q[2]; rrw = q[3];
            }
        }
        if (section.contains("text_opacity")) {
            int rawOp = section.getInt("text_opacity", opacity & 0xFF);
            opacity = (byte) (rawOp < 0 ? -1 : Math.min(127, rawOp));
        }

        // --- Syntax sugar ---

        // move: (incremental offset applied on top of base translation)
        ConfigurationSection move = section.getConfigurationSection("move");
        if (move != null) {
            ty += resolveSugarValue(move, "up",       deltas, D_MOVE_UP);
            ty -= resolveSugarValue(move, "down",     deltas, D_MOVE_DOWN);
            tx += resolveSugarValue(move, "right",    deltas, D_MOVE_RIGHT);
            tx -= resolveSugarValue(move, "left",     deltas, D_MOVE_LEFT);
            tz -= resolveSugarValue(move, "forward",  deltas, D_MOVE_FWD);
            tz += resolveSugarValue(move, "backward", deltas, D_MOVE_BWD);
        }

        // size:
        Object sizeVal = section.get("size");
        if (sizeVal instanceof ConfigurationSection sizeSection) {
            Object wVal = sizeSection.get("width");
            if (wVal != null) { float w = resolveAbsolute(wVal, sx, deltas, D_SIZE);  sx = w; sz = w; }
            Object hVal = sizeSection.get("height");
            if (hVal != null) { sy = resolveAbsolute(hVal, sy, deltas, D_SIZE_H); }
        } else if (sizeVal != null) {
            float s = resolveAbsolute(sizeVal, sx, deltas, D_SIZE);
            deltas[D_SIZE_H] = s - sy;
            sx = s; sy = s; sz = s;
        }

        // tilt: (Z-axis rotation via right_rotation quaternion)
        ConfigurationSection tilt = section.getConfigurationSection("tilt");
        if (tilt != null) {
            float degrees = resolveSugarValue(tilt, "left", deltas, D_TILT_LEFT)
                          - resolveSugarValue(tilt, "right", deltas, D_TILT_RIGHT);
            float[] q = eulerToQuaternion(0f, 0f, degrees);
            rrx = q[0]; rry = q[1]; rrz = q[2]; rrw = q[3];
        }

        // spin: (Y-axis rotation via right_rotation quaternion, combined with tilt if present)
        ConfigurationSection spin = section.getConfigurationSection("spin");
        if (spin != null) {
            float degrees = resolveSugarValue(spin, "left", deltas, D_SPIN_LEFT)
                          - resolveSugarValue(spin, "right", deltas, D_SPIN_RIGHT);
            float[] q = eulerToQuaternion(0f, degrees, 0f);
            // compose with existing right rotation
            float[] combined = multiplyQuaternion(
                    new float[]{rrx, rry, rrz, rrw},
                    q
            );
            rrx = combined[0]; rry = combined[1]; rrz = combined[2]; rrw = combined[3];
        }

        // opacity:
        Object opacityRaw = section.get("opacity");
        if (opacityRaw instanceof Number num) {
            byte newOp = (byte) Math.max(0, Math.min(127, num.intValue()));
            deltas[D_OPACITY] = (float) ((newOp & 0xFF) - (opacity & 0xFF));
            opacity = newOp;
        } else if (opacityRaw instanceof String opacityStr) {
            opacityStr = opacityStr.trim();
            if (!opacityStr.isEmpty()) {
                char first = opacityStr.charAt(0);
                if (first == '+' || first == '-') {
                    // Delta modifier: +N, -N, +N%, -N%
                    float newDelta = applyDeltaModifier(opacityStr, deltas[D_OPACITY]);
                    if (!Float.isNaN(newDelta)) {
                        deltas[D_OPACITY] = newDelta;
                        int newVal = Math.round((opacity & 0xFF) + newDelta);
                        opacity = (byte) Math.max(0, Math.min(127, newVal));
                    }
                } else if (opacityStr.endsWith("%")) {
                    double pct = Double.parseDouble(opacityStr.substring(0, opacityStr.length() - 1));
                    byte newOp = (byte) Math.max(0, Math.min(127, (int) Math.round(pct / 100.0 * 127)));
                    deltas[D_OPACITY] = (float) ((newOp & 0xFF) - (opacity & 0xFF));
                    opacity = newOp;
                } else {
                    byte newOp = (byte) Math.max(0, Math.min(127, Integer.parseInt(opacityStr)));
                    deltas[D_OPACITY] = (float) ((newOp & 0xFF) - (opacity & 0xFF));
                    opacity = newOp;
                }
            }
        }

        return new TransformSnapshot(tx, ty, tz, sx, sy, sz, lrx, lry, lrz, lrw, rrx, rry, rrz, rrw, opacity);
    }

    // -------------------------------------------------------------------------
    // Delta modifier helpers
    // -------------------------------------------------------------------------

    /**
     * Parses a delta modifier string ({@code +N}, {@code -N}, {@code +N%}, {@code -N%})
     * and returns the new delta based on {@code prevDelta}.
     * Returns {@link Float#NaN} if the string is malformed.
     */
    private static float applyDeltaModifier(String str, float prevDelta) {
        try {
            if (str.endsWith("%")) {
                float pct = Float.parseFloat(str.substring(0, str.length() - 1));
                return prevDelta * (1f + pct / 100f);
            } else {
                return prevDelta + Float.parseFloat(str);
            }
        } catch (NumberFormatException e) {
            return Float.NaN;
        }
    }

    /**
     * Resolves a sugar sub-key that may be a delta modifier.
     * Used for additive fields where the value itself IS the delta (move, tilt, spin).
     * <ul>
     *   <li>{@code 0.3} (Number) → absolute delta</li>
     *   <li>{@code "+0.1"} (String) → prevDelta + 0.1</li>
     *   <li>{@code "-0.1"} (String) → prevDelta − 0.1</li>
     *   <li>{@code "+30%"} (String) → prevDelta × 1.3</li>
     *   <li>{@code "-40%"} (String) → prevDelta × 0.6</li>
     * </ul>
     */
    private static float resolveSugarValue(ConfigurationSection section, String key,
                                            float[] deltas, int idx) {
        Object raw = section.get(key);
        if (raw == null) return 0f;
        if (raw instanceof Number num) {
            float v = num.floatValue();
            deltas[idx] = v;
            return v;
        }
        String str = raw.toString().trim();
        if (str.isEmpty()) return 0f;
        char first = str.charAt(0);
        if (first != '+' && first != '-') {
            try { float v = Float.parseFloat(str); deltas[idx] = v; return v; }
            catch (NumberFormatException e) { return 0f; }
        }
        float newDelta = applyDeltaModifier(str, deltas[idx]);
        if (Float.isNaN(newDelta)) return 0f;
        deltas[idx] = newDelta;
        return newDelta;
    }

    /**
     * Resolves a value for an absolute-target field (size components).
     * Tracks delta as {@code newValue − prevBase} for subsequent modifier frames.
     */
    private static float resolveAbsolute(Object raw, float prevBase,
                                          float[] deltas, int idx) {
        if (raw instanceof Number num) {
            float v = num.floatValue();
            deltas[idx] = v - prevBase;
            return v;
        }
        String str = raw.toString().trim();
        if (str.isEmpty()) return prevBase;
        char first = str.charAt(0);
        if (first != '+' && first != '-') {
            try { float v = Float.parseFloat(str); deltas[idx] = v - prevBase; return v; }
            catch (NumberFormatException e) { return prevBase; }
        }
        float newDelta = applyDeltaModifier(str, deltas[idx]);
        if (Float.isNaN(newDelta)) return prevBase;
        deltas[idx] = newDelta;
        return prevBase + newDelta;
    }

    // -------------------------------------------------------------------------
    // Math helpers
    // -------------------------------------------------------------------------

    /** Converts ZYX euler angles (degrees) to a quaternion [x, y, z, w]. */
    public static float[] eulerToQuaternion(float ex, float ey, float ez) {
        double hx = Math.toRadians(ex) * 0.5;
        double hy = Math.toRadians(ey) * 0.5;
        double hz = Math.toRadians(ez) * 0.5;
        double cx = Math.cos(hx), sxr = Math.sin(hx);
        double cy = Math.cos(hy), syr = Math.sin(hy);
        double cz = Math.cos(hz), szr = Math.sin(hz);
        return new float[]{
                (float)(sxr * cy * cz - cx * syr * szr),
                (float)(cx  * syr * cz + sxr * cy * szr),
                (float)(cx  * cy * szr - sxr * syr * cz),
                (float)(cx  * cy * cz  + sxr * syr * szr)
        };
    }

    /** Hamilton product of two quaternions [x, y, z, w]. */
    private static float[] multiplyQuaternion(float[] a, float[] b) {
        return new float[]{
                a[3]*b[0] + a[0]*b[3] + a[1]*b[2] - a[2]*b[1],
                a[3]*b[1] - a[0]*b[2] + a[1]*b[3] + a[2]*b[0],
                a[3]*b[2] + a[0]*b[1] - a[1]*b[0] + a[2]*b[3],
                a[3]*b[3] - a[0]*b[0] - a[1]*b[1] - a[2]*b[2]
        };
    }

    private static float toFloat(Object o) {
        return o instanceof Number n ? n.floatValue() : 0f;
    }
}
