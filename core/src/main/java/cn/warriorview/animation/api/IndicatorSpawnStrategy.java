package cn.warriorview.animation.api;

import com.github.retrooper.packetevents.util.Vector3d;

import cn.warriorview.util.LocationUtil;

/**
 * Determines the initial spawn position of a TextDisplay damage indicator.
 *
 * <p>Each constant wraps one positioning strategy.  Call {@link #resolve} with
 * all attacker/victim parameters; unused parameters are simply ignored by the
 * variant.</p>
 *
 * <h3>Quick selection guide</h3>
 * <pre>
 *  PROJECTED   – melee / directed skills      (trigonometry, ~6 ops)
 *  CLAMPED     – AoE / DoT / splash damage    (no trig, fastest)
 *  RAY_TRACED  – sniper / headshot precision  (Liang-Barsky, most accurate)
 *  ATTACKER    – self-buff / combo floaters   (trivial, returns attacker eye)
 * </pre>
 */
public enum IndicatorSpawnStrategy {

    /**
     * <b>Algorithm 1 — Dot Product Projection</b>
     *
     * <p>Projects the attacker's gaze direction onto the victim's body centre,
     * then pulls back by half the victim's width to avoid the indicator
     * clipping through geometry.</p>
     *
     * <p>Best for: melee hits, targeted spell projectiles.</p>
     */
    PROJECTED {
        @Override
        public Vector3d resolve(double eyeX, double eyeY, double eyeZ,
                                float pitch, float yaw,
                                double vX, double vY, double vZ,
                                double vH, double vW) {
            return LocationUtil.getProjectedHitLocation(
                    eyeX, eyeY, eyeZ, pitch, yaw, vX, vY, vZ, vH, vW);
        }
    },

    /**
     * <b>Algorithm 2 — Expanded AABB Clamping</b>
     *
     * <p>Geometrically clamps the attacker's eye position to the padded
     * bounding box of the victim.  No trigonometry; fastest of all variants.</p>
     *
     * <p>Best for: AoE damage, persistent damage-over-time ticks, splash.</p>
     */
    CLAMPED {
        @Override
        public Vector3d resolve(double eyeX, double eyeY, double eyeZ,
                                float pitch, float yaw,
                                double vX, double vY, double vZ,
                                double vH, double vW) {
            return LocationUtil.getClampedHitLocation(
                    eyeX, eyeY, eyeZ, vX, vY, vZ, vH, vW);
        }
    },

    /**
     * <b>Algorithm 3 — Liang-Barsky Ray-AABB Intersection</b>
     *
     * <p>Casts a ray from the attacker's eye along their look direction and
     * finds the exact entry point on the victim's bounding box.  Zero GC;
     * highest spatial accuracy.</p>
     *
     * <p>Falls back to {@code (vX, vY + vH * 0.75, vZ)} when the ray misses
     * (e.g., lag compensation desync).</p>
     *
     * <p>Best for: sniper rifles, headshot detection, pinpoint single-target skills.</p>
     */
    RAY_TRACED {
        @Override
        public Vector3d resolve(double eyeX, double eyeY, double eyeZ,
                                float pitch, float yaw,
                                double vX, double vY, double vZ,
                                double vH, double vW) {
            return LocationUtil.getRayTracedHitLocation(
                    eyeX, eyeY, eyeZ, pitch, yaw, vX, vY, vZ, vH, vW);
        }
    },

    /**
     * <b>Attacker exact eye position</b>
     *
     * <p>The indicator spawns at the attacker's eye rather than at the victim.
     * {@code pitch}, {@code yaw}, and all victim parameters are ignored.</p>
     *
     * <p>Best for: outgoing damage floaters, self-buff numbers, combo counters,
     * or any effect that should visually "come from" the attacker.</p>
     */
    ATTACKER {
        @Override
        public Vector3d resolve(double eyeX, double eyeY, double eyeZ,
                                float pitch, float yaw,
                                double vX, double vY, double vZ,
                                double vH, double vW) {
            return new Vector3d(eyeX, eyeY, eyeZ);
        }
    };

    // ── Abstract contract ─────────────────────────────────────────────────────

    /**
     * Computes the world-space spawn coordinate for a TextDisplay entity.
     *
     * @param eyeX  attacker eye position X
     * @param eyeY  attacker eye position Y
     * @param eyeZ  attacker eye position Z
     * @param pitch attacker pitch in degrees
     * @param yaw   attacker yaw   in degrees
     * @param vX    victim foot position X
     * @param vY    victim foot position Y  (bottom of AABB)
     * @param vZ    victim foot position Z
     * @param vH    victim bounding-box height
     * @param vW    victim bounding-box width (also used as depth)
     * @return the spawn position for the TextDisplay entity
     */
    public abstract Vector3d resolve(double eyeX, double eyeY, double eyeZ,
                                     float pitch, float yaw,
                                     double vX, double vY, double vZ,
                                     double vH, double vW);
}
