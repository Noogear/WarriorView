package cn.warriorview.animation.parse;

import cn.warriorview.animation.api.Billboard;
import cn.warriorview.animation.data.DisplaySettings;
import cn.warriorview.animation.data.OffsetExpr;
import gloomlib.math.api.MathEngine;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Parses the {@code settings:} block of an animation definition into a
 * {@link DisplaySettings} record.
 *
 * <p>gloomlib-math is used to compile {@code offset.x/y/z} into
 * {@link gloomlib.math.api.CompiledMathExpression} instances once at load time.</p>
 *
 * <h3>YAML structure</h3>
 * <pre>
 * settings:
 *   billboard: center          # FIXED | VERTICAL | HORIZONTAL | CENTER
 *   see-through: false
 *   text-shadow: false
 *   background: "#40000000"    # ARGB hex string, or "transparent"
 *   view-range: 1.0            # client-side visibility multiplier
 *   teleport-duration: 0       # position/rotation interpolation ticks
 *   brightness: -1             # packed (blockLight &lt;&lt; 4 | skyLight &lt;&lt; 20), -1 = world light
 *   shadow-radius: 0           # ground shadow radius
 *   shadow-strength: 1.0       # ground shadow strength
 *   glow-color: 0              # RGB glow outline colour (0 = default)
 *   line-width: 200            # max text line width in pixels
 *   offset:
 *     x: "r * 0.6 - 0.3"
 *     y: "r * 0.5"
 *     z: "r * 0.6 - 0.3"
 * </pre>
 */
public final class SettingsParser {

    private SettingsParser() {}

    /**
     * Parses the {@code settings} sub-section and returns a fully constructed
     * {@link DisplaySettings}. Falls back to {@link DisplaySettings#DEFAULT} if
     * the section is {@code null}.
     *
     * @param section the {@code settings:} configuration section (may be null)
     * @return parsed (or default) display settings
     */
    public static DisplaySettings parse(ConfigurationSection section) {
        if (section == null) return DisplaySettings.DEFAULT;

        Billboard billboard = Billboard.fromName(
                section.getString("billboard", "CENTER"),
                Billboard.CENTER
        );

        boolean seeThrough  = section.getBoolean("see-through",  false);
        boolean textShadow  = section.getBoolean("text-shadow",  false);
        float   viewRange   = (float) section.getDouble("view-range", 1.0);

        int backgroundColor = parseBackground(section.getString("background"));

        int   teleportDuration = section.getInt("teleport-duration", 0);
        int   brightness       = section.getInt("brightness", -1);
        float shadowRadius     = (float) section.getDouble("shadow-radius", 0.0);
        float shadowStrength   = (float) section.getDouble("shadow-strength", 1.0);
        int   glowColor        = section.getInt("glow-color", 0);
        int   lineWidth        = section.getInt("line-width", 200);

        OffsetExpr offset = parseOffset(section.getConfigurationSection("offset"));

        return new DisplaySettings(billboard, seeThrough, textShadow, backgroundColor, viewRange,
                teleportDuration, brightness, shadowRadius, shadowStrength, glowColor, lineWidth, offset);
    }

    // -------------------------------------------------------------------------

    private static int parseBackground(String raw) {
        if (raw == null || raw.isBlank()) return DisplaySettings.DEFAULT_BACKGROUND;
        if ("transparent".equalsIgnoreCase(raw.trim())) return 0;
        try {
            String cleaned = raw.trim();
            if (cleaned.startsWith("#")) cleaned = cleaned.substring(1);
            return (int) Long.parseLong(cleaned, 16);
        } catch (NumberFormatException e) {
            return DisplaySettings.DEFAULT_BACKGROUND;
        }
    }

    private static OffsetExpr parseOffset(ConfigurationSection section) {
        if (section == null) return OffsetExpr.ZERO;

        // toExprString: supports both plain numbers (e.g. `x: 0.3`) and
        // math expression strings (e.g. `x: "r * 0.6 - 0.3"`)
        String xExpr = toExprString(section, "x", "0");
        String yExpr = toExprString(section, "y", "0");
        String zExpr = toExprString(section, "z", "0");

        try {
            return new OffsetExpr(
                    MathEngine.compile(xExpr, OffsetExpr.VARS),
                    MathEngine.compile(yExpr, OffsetExpr.VARS),
                    MathEngine.compile(zExpr, OffsetExpr.VARS)
            );
        } catch (Exception e) {
            System.err.println("[WarriorView] Failed to compile offset expression: " + e.getMessage());
            return OffsetExpr.ZERO;
        }
    }

    /**
     * Reads a YAML value that can be either a plain number or a math expression string.
     * <ul>
     *   <li>{@code x: 0.3}         → {@code "0.3"} (constant expression)</li>
     *   <li>{@code x: "r * 0.6"}   → {@code "r * 0.6"}</li>
     * </ul>
     */
    static String toExprString(ConfigurationSection section, String key, String defaultExpr) {
        Object val = section.get(key);
        if (val instanceof Number n) return Double.toString(n.doubleValue());
        if (val instanceof String s && !s.isBlank()) return s;
        return defaultExpr;
    }
}
