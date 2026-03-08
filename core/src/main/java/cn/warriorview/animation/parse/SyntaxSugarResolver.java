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

    /**
     * Resolves a keyframe YAML section into a {@link TransformSnapshot} by merging
     * sugar fields and raw-NMS fields on top of {@code base}.
     *
     * @param base    the previous frame's snapshot (used for incremental move offsets)
     * @param section the YAML section for this keyframe entry
     * @return the fully-resolved {@link TransformSnapshot} for this frame
     */
    public static TransformSnapshot resolve(TransformSnapshot base, ConfigurationSection section) {
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
            opacity = (byte) section.getInt("text_opacity", opacity & 0xFF);
        }

        // --- Syntax sugar ---

        // move: (incremental offset applied on top of base translation)
        ConfigurationSection move = section.getConfigurationSection("move");
        if (move != null) {
            ty += (float) move.getDouble("up",       0);
            ty -= (float) move.getDouble("down",     0);
            tx += (float) move.getDouble("right",    0);
            tx -= (float) move.getDouble("left",     0);
            tz -= (float) move.getDouble("forward",  0);
            tz += (float) move.getDouble("backward", 0);
        }

        // size:
        Object sizeVal = section.get("size");
        if (sizeVal instanceof Number num) {
            float s = num.floatValue();
            sx = s; sy = s; sz = s;
        } else if (sizeVal instanceof ConfigurationSection sizeSection) {
            float w = (float) sizeSection.getDouble("width",  sx);
            float h = (float) sizeSection.getDouble("height", sy);
            sx = w; sy = h; sz = w;
        }

        // tilt: (Z-axis rotation via right_rotation quaternion)
        ConfigurationSection tilt = section.getConfigurationSection("tilt");
        if (tilt != null) {
            float degrees = (float) (tilt.getDouble("left", 0) - tilt.getDouble("right", 0));
            float[] q = eulerToQuaternion(0f, 0f, degrees);
            rrx = q[0]; rry = q[1]; rrz = q[2]; rrw = q[3];
        }

        // spin: (Y-axis rotation via right_rotation quaternion, combined with tilt if present)
        ConfigurationSection spin = section.getConfigurationSection("spin");
        if (spin != null) {
            float degrees = (float) (spin.getDouble("left", 0) - spin.getDouble("right", 0));
            float[] q = eulerToQuaternion(0f, degrees, 0f);
            // compose with existing right rotation
            float[] combined = multiplyQuaternion(
                    new float[]{rrx, rry, rrz, rrw},
                    q
            );
            rrx = combined[0]; rry = combined[1]; rrz = combined[2]; rrw = combined[3];
        }

        // opacity:
        String opacityStr = section.getString("opacity");
        if (opacityStr != null) {
            opacityStr = opacityStr.trim();
            if (opacityStr.endsWith("%")) {
                double pct = Double.parseDouble(opacityStr.substring(0, opacityStr.length() - 1));
                opacity = (byte) Math.round(pct / 100.0 * 127);
            } else {
                opacity = (byte) Integer.parseInt(opacityStr);
            }
        }

        return new TransformSnapshot(tx, ty, tz, sx, sy, sz, lrx, lry, lrz, lrw, rrx, rry, rrz, rrw, opacity);
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
