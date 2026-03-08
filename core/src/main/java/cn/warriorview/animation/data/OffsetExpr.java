package cn.warriorview.animation.data;

import gloomlib.math.api.MathEngine;

/**
 * Per-instance random offset defined by three gloomlib-math expressions.
 * <p>
 * Each expression may reference a single variable {@code r} which is drawn once
 * from [0, 1) per animation instance.  A constant expression is also valid.
 * <p>
 * Example YAML:
 * <pre>
 *   offset:
 *     x: "r * 0.6 - 0.3"
 *     y: "r * 0.5"
 *     z: "r * 0.6 - 0.3"
 * </pre>
 */
public record OffsetExpr(
        MathEngine.CompiledMathExpression x,
        MathEngine.CompiledMathExpression y,
        MathEngine.CompiledMathExpression z
) {
    /** Canonical variable names passed to MathEngine.compile(). */
    public static final String[] VARS = {"r"};

    /** An identity offset that always evaluates to (0, 0, 0). */
    public static final OffsetExpr ZERO = new OffsetExpr(
            args -> 0.0,
            args -> 0.0,
            args -> 0.0
    );

    /**
     * Evaluates the three expressions with the given random value.
     *
     * @param r per-instance random in [0, 1)
     * @return [dx, dy, dz] as double[3]
     */
    public double[] evaluate(double r) {
        double[] argv = {r};
        return new double[]{
                x.evaluate(argv),
                y.evaluate(argv),
                z.evaluate(argv)
        };
    }

    /**
     * Evaluates into a pre-allocated float array to avoid allocation on hot paths.
     *
     * @param r     per-instance random in [0, 1)
     * @param out   output array of length >= 3; indices 0=dx, 1=dy, 2=dz
     */
    public void evaluateInto(double r, float[] out) {
        double[] argv = {r};
        out[0] = (float) x.evaluate(argv);
        out[1] = (float) y.evaluate(argv);
        out[2] = (float) z.evaluate(argv);
    }
}
