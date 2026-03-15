package cn.warriorview.animation.definition;

import cn.warriorview.animation.api.AnimationType;
import cn.warriorview.animation.api.Space;
import cn.warriorview.animation.data.BakedFrame;
import cn.warriorview.animation.data.BakedSequence;
import cn.warriorview.animation.data.DisplaySettings;
import cn.warriorview.animation.data.TransformSnapshot;
import gloomlib.math.api.MathEngine;

/**
 * A math-equation-based animation definition.
 *
 * <p>At spawn time, a random value {@code r ∈ [0,1)} is sampled once per instance.
 * The equation expressions are then evaluated for each sample tick to produce a
 * {@link BakedSequence}. This one-time allocation happens before scheduling and
 * eliminates all per-tick computation during actual playback.</p>
 *
 * <p>All ten expression fields accept two variables: {@code t} (elapsed ticks) and
 * {@code r} (per-instance random).</p>
 *
 * @param name           unique animation identifier
 * @param settings       shared display settings
 * @param durationTicks  total animation duration in ticks
 * @param sampleInterval ticks between consecutive sample points (usually 1)
 * @param posX           translation X expression (t, r) → blocks
 * @param posY           translation Y expression (t, r) → blocks
 * @param posZ           translation Z expression (t, r) → blocks
 * @param scaleX         scale X expression (t, r) → multiplier
 * @param scaleY         scale Y expression (t, r) → multiplier
 * @param scaleZ         scale Z expression (t, r) → multiplier
 * @param rotX           rotation axis-X expression (t, r) → degrees (converted internally)
 * @param rotY           rotation axis-Y expression (t, r) → degrees
 * @param rotZ           rotation axis-Z expression (t, r) → degrees
 * @param opacity        text-opacity expression (t, r) → [0, 127]; -1 = default
 */
public record EquationDef(
        String                name,
        DisplaySettings       settings,
        Space                 space,
        int                   durationTicks,
        int                   sampleInterval,
        MathEngine.CompiledMathExpression posX,
        MathEngine.CompiledMathExpression posY,
        MathEngine.CompiledMathExpression posZ,
        MathEngine.CompiledMathExpression scaleX,
        MathEngine.CompiledMathExpression scaleY,
        MathEngine.CompiledMathExpression scaleZ,
        MathEngine.CompiledMathExpression rotX,
        MathEngine.CompiledMathExpression rotY,
        MathEngine.CompiledMathExpression rotZ,
        MathEngine.CompiledMathExpression opacity
) implements AnimationDef {

    /** Variable names forwarded to every expression evaluation. */
    public static final String[] VARS = {"t", "r"};

    @Override
    public AnimationType type() {
        return AnimationType.EQUATION;
    }

    @Override
    public int totalDurationTicks() {
        return durationTicks;
    }

    /**
     * Bakes this equation animation into an immutable {@link BakedSequence} for
     * the given per-instance random value.  Called once at spawn time; the result
     * is used directly by {@code AnimationPlayer} for zero-GC frame scheduling.
     *
     * @param r per-instance random in [0, 1)
     * @return a freshly allocated BakedSequence (share-nothing, owned by the play instance)
     */
    @Override
    public BakedSequence bake(double r) {
        int count = Math.max(1, (durationTicks / sampleInterval) + 1);
        BakedFrame[] frames = new BakedFrame[count];

        // Reuse argument array across iterations: args[0] = t (updated per loop), args[1] = r (constant)
        double[] args = {0.0, r};
        // Reuse quaternion buffer to avoid N × float[4] allocations
        float[] qBuf = new float[4];

        for (int i = 0; i < count; i++) {
            int evalTick = i * sampleInterval;  // evaluate expressions at this tick
            // Send frame at the START of its interpolation segment so the
            // MC client can interpolate toward this target over sampleInterval ticks.
            // frame[0] = initial state at spawn; frame[1..n] = first target sent
            // at the same tick as the previous frame's evaluation tick.
            int sendTick = i == 0 ? 0 : (i - 1) * sampleInterval;
            args[0] = evalTick;

            float tx = (float) posX.evaluate(args);
            float ty = (float) posY.evaluate(args);
            float tz = (float) posZ.evaluate(args);

            float sx = (float) scaleX.evaluate(args);
            float sy = (float) scaleY.evaluate(args);
            float sz = (float) scaleZ.evaluate(args);

            // Convert Euler angles (degrees) to quaternion for right rotation
            float rx = (float) rotX.evaluate(args);
            float ry = (float) rotY.evaluate(args);
            float rz = (float) rotZ.evaluate(args);
            eulerToQuaternion(rx, ry, rz, qBuf);

            double opD = opacity.evaluate(args);
            byte opB = opD < 0 ? (byte) -1 : (byte) Math.min(127, (int) opD);

            TransformSnapshot snap = new TransformSnapshot(
                    tx, ty, tz,
                    sx, sy, sz,
                    0f, 0f, 0f, 1f,   // left rotation = identity
                    qBuf[0], qBuf[1], qBuf[2], qBuf[3],
                    opB
            );

            // delay=0: MC client processes "start interpolation at current tick"
            // when index 8 is explicitly included in the metadata packet.
            frames[i] = new BakedFrame(sendTick, 0, sampleInterval, snap);
        }

        return new BakedSequence(frames, durationTicks, settings);
    }

    /**
     * 视角空间专用烘焙：在求值循环内直接旋转 tx/tz，省去 {@link cn.warriorview.animation.data.BakedSequence#rotateXZ}
     * 产生的额外一轮分配。相比先 {@link #bake} 再 rotateXZ，帧数组只分配一次。
     * <p>
     * 仅由 {@code AnimationPlayer} 在 {@code space == VIEW} 时调用。
     *
     * @param r   per-instance random in [0, 1)
     * @param cos {@code cos(-attackerYawRad)}
     * @param sin {@code sin(-attackerYawRad)}
     */
    public BakedSequence bakeRotated(double r, float cos, float sin) {
        int count = Math.max(1, (durationTicks / sampleInterval) + 1);
        BakedFrame[] frames = new BakedFrame[count];
        double[] args = {0.0, r};
        float[] qBuf = new float[4];

        for (int i = 0; i < count; i++) {
            int evalTick = i * sampleInterval;
            int sendTick = i == 0 ? 0 : (i - 1) * sampleInterval;
            args[0] = evalTick;

            float txV = (float) posX.evaluate(args);
            float ty  = (float) posY.evaluate(args);
            float tzV = (float) posZ.evaluate(args);
            // 内联 view→world 旋转：省去中间 BakedSequence 分配
            float tx =  txV * cos + tzV * sin;
            float tz = -txV * sin + tzV * cos;

            float sx = (float) scaleX.evaluate(args);
            float sy = (float) scaleY.evaluate(args);
            float sz = (float) scaleZ.evaluate(args);

            float rx = (float) rotX.evaluate(args);
            float ry = (float) rotY.evaluate(args);
            float rz = (float) rotZ.evaluate(args);
            eulerToQuaternion(rx, ry, rz, qBuf);

            double opD = opacity.evaluate(args);
            byte opB = opD < 0 ? (byte) -1 : (byte) Math.min(127, (int) opD);

            frames[i] = new BakedFrame(sendTick, 0, sampleInterval, new TransformSnapshot(
                    tx, ty, tz,
                    sx, sy, sz,
                    0f, 0f, 0f, 1f,
                    qBuf[0], qBuf[1], qBuf[2], qBuf[3],
                    opB));
        }

        return new BakedSequence(frames, durationTicks, settings);
    }

    /** Converts ZYX Euler angles (degrees) to a quaternion, writing result into {@code out[0..3]} (x,y,z,w). */
    private static void eulerToQuaternion(float ex, float ey, float ez, float[] out) {
        double hx = Math.toRadians(ex) * 0.5;
        double hy = Math.toRadians(ey) * 0.5;
        double hz = Math.toRadians(ez) * 0.5;

        double cx = Math.cos(hx), sx2 = Math.sin(hx);
        double cy = Math.cos(hy), sy2 = Math.sin(hy);
        double cz = Math.cos(hz), sz2 = Math.sin(hz);

        out[0] = (float) (sx2 * cy * cz - cx * sy2 * sz2);
        out[1] = (float) (cx * sy2 * cz + sx2 * cy * sz2);
        out[2] = (float) (cx * cy * sz2 - sx2 * sy2 * cz);
        out[3] = (float) (cx * cy * cz  + sx2 * sy2 * sz2);
    }
}
