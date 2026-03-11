package cn.warriorview.animation.parse;

import cn.warriorview.animation.api.Space;
import cn.warriorview.animation.data.DisplaySettings;
import cn.warriorview.animation.definition.EquationDef;
import gloomlib.math.api.MathEngine;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Parses a {@code type: equation} animation section into an {@link EquationDef}.
 *
 * <p>All ten math expressions ({@code position.x/y/z}, {@code scale.x/y/z},
 * {@code rotation.x/y/z}, {@code opacity}) are compiled at load time via
 * {@link MathEngine}.  Runtime baking (called once per instance at spawn time)
 * only evaluates these pre-compiled expressions – it never re-parses strings.</p>
 *
 * <h3>YAML structure</h3>
 * <pre>
 * my_fountain:
 *   type: equation
 *   settings: { ... }
 *   duration: 40
 *   sample_interval: 2
 *   math:
 *     position:
 *       x: "sin(t * 0.2) * 0.5"
 *       y: "t * 0.05"
 *       z: "cos(t * 0.2) * 0.5"
 *     scale:
 *       x: "1"
 *       y: "1"
 *       z: "1"
 *     rotation:
 *       x: "0"
 *       y: "t * 9"
 *       z: "0"
 *     opacity: "127 - t * 2"
 * </pre>
 */
public final class EquationParser {

    private EquationParser() {}

    /**
     * Parses the animation section and returns an {@link EquationDef} with all
     * expressions pre-compiled.
     *
     * @param name    the animation's unique identifier
     * @param section the YAML section under {@code <name>:}
     * @return a ready-to-bake {@link EquationDef}, or {@code null} on parse error
     */
    public static EquationDef parse(String name, ConfigurationSection section) {
        DisplaySettings settings = SettingsParser.parse(section.getConfigurationSection("settings"));
        Space space = Space.fromName(section.getString("space"));

        // duration supports plain number or math expression (no variables, evaluated once)
        int duration = parseDuration(section, name);
        int sampleInterval = section.getInt("sample_interval", 1);
        if (sampleInterval < 1) sampleInterval = 1;

        ConfigurationSection math = section.getConfigurationSection("math");
        if (math == null) {
            System.err.println("[WarriorView] Equation animation '" + name + "' is missing a 'math:' section.");
            return null;
        }

        ConfigurationSection pos  = math.getConfigurationSection("position");
        ConfigurationSection scl  = math.getConfigurationSection("scale");
        ConfigurationSection rot  = math.getConfigurationSection("rotation");

        try {
            MathEngine.CompiledMathExpression posX = compile(pos, "x", "0", name);
            MathEngine.CompiledMathExpression posY = compile(pos, "y", "0", name);
            MathEngine.CompiledMathExpression posZ = compile(pos, "z", "0", name);
            MathEngine.CompiledMathExpression sclX = compile(scl, "x", "1", name);
            MathEngine.CompiledMathExpression sclY = compile(scl, "y", "1", name);
            MathEngine.CompiledMathExpression sclZ = compile(scl, "z", "1", name);
            MathEngine.CompiledMathExpression rotX = compile(rot, "x", "0", name);
            MathEngine.CompiledMathExpression rotY = compile(rot, "y", "0", name);
            MathEngine.CompiledMathExpression rotZ = compile(rot, "z", "0", name);

            String opacityExpr = math.getString("opacity", "127");
            MathEngine.CompiledMathExpression opacity = MathEngine.compile(opacityExpr, EquationDef.VARS);

            return new EquationDef(
                    name, settings, space, duration, sampleInterval,
                    posX, posY, posZ,
                    sclX, sclY, sclZ,
                    rotX, rotY, rotZ,
                    opacity
            );
        } catch (Exception e) {
            System.err.println("[WarriorView] Failed to compile equation for '" + name + "': " + e.getMessage());
            return null;
        }
    }

    // -------------------------------------------------------------------------

    /**
     * Parses {@code duration}, accepting both a plain integer and a constant
     * math expression string ({@code "${multiply} * 20"} style).
     */
    private static int parseDuration(ConfigurationSection section, String animName) {
        Object val = section.get("duration");
        if (val instanceof Number n) return n.intValue();
        if (val instanceof String s) {
            try {
                return (int) MathEngine.compile(s).evaluate();
            } catch (Exception e) {
                plugin_warn(animName, "duration expression '" + s + "': " + e.getMessage());
            }
        }
        return 20; // default
    }

    private static void plugin_warn(String name, String msg) {
        System.err.println("[WarriorView] Animation '" + name + "' – " + msg);
    }

    private static MathEngine.CompiledMathExpression compile(
            ConfigurationSection section,
            String key,
            String defaultExpr,
            String animName
    ) throws Exception {
        String expr = (section != null) ? section.getString(key, defaultExpr) : defaultExpr;
        return MathEngine.compile(expr, EquationDef.VARS);
    }
}
