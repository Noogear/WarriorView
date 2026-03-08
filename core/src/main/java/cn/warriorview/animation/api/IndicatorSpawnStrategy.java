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
 *  AIM     – melee / directed attacks          (gaze-depth projection, ~6 trig ops)
 *  SURFACE – AoE / DoT / direction-agnostic    (AABB clamp, no trig, fastest)
 *  IMPACT  – precise melee / projectile hit    (ray-AABB, hard ≤5-block limit)
 *  ORIGIN  – self-damage / healing / buffs     (trivial, returns attacker eye)
 * </pre>
 *
 * <p><b>Note on projectile attacks:</b> when a projectile entity deals damage,
 * the {@code attacker} passed to {@link #resolve} is the projectile entity itself
 * (position = impact point, pitch/yaw = flight direction at moment of collision).
 * All strategies handle this correctly, but {@link #IMPACT} is the most accurate
 * since the projectile is right at the victim's AABB entry surface ({@code tMin ≈ 0}).</p>
 */
public enum IndicatorSpawnStrategy {

    /**
     * <b>Algorithm 1 — Dot-Product Gaze Projection</b>
     *
     * <p>Scalar-projects the attacker-to-victim vector onto the normalised gaze
     * direction, then places the indicator at that depth along the gaze ray,
     * pulled back by {@code vW / 2 + 0.3} to sit just outside the victim's
     * near surface as seen from the attacker.</p>
     *
     * <p>For projectile hits the "attacker" is the projectile entity itself
     * (position ≈ AABB entry surface, direction = flight direction), so the
     * projection naturally places the indicator just outside the impact face.</p>
     *
     * <p>Best for: melee attacks and any directed hit where the attacker is
     * facing the victim.  Degrades gracefully for off-angle hits (e.g. AoE
     * side-swipe) but is not the most accurate in those cases.</p>
     */
    AIM {
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
     * <p>Clamps the attacker's eye position to the victim's bounding box
     * expanded by {@code 0.3} on every side.  The indicator appears at the
     * nearest point on the victim's body surface to the attacker.  Uses no
     * trigonometry; fastest of all variants.</p>
     *
     * <p>When the attacker is {@code null} (e.g. DoT with no source), it
     * defaults to the victim entity, so the eye position lies within the
     * victim's own AABB — the indicator then appears at the victim's body
     * centre area, which is ideal for directionless damage ticks.</p>
     *
     * <p>Best for: AoE damage, splash, persistent DoT, and any damage whose
     * source direction is irrelevant or unknown.</p>
     */
    SURFACE {
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
     * finds the exact AABB entry point, then pulls back {@code 0.3} in the
     * ray direction to sit on the surface.  Zero GC; highest directional
     * accuracy of all variants.</p>
     *
     * <p><b>Hard range limit: 5 blocks.</b>  The intersection is rejected when
     * {@code tMin > 5.0} or {@code tMin < 0} (attacker's eye has already
     * passed the entry surface).  In those cases the method falls back to
     * {@code (vX, vY + vH × 0.75, vZ)} — the victim's upper-body centre.</p>
     *
     * <p>Fall-back triggers: (1) ray does not intersect the AABB, (2) nearest
     * intersection is more than 5 blocks away, (3) attacker eye is already
     * inside or past the entry face (common when a projectile entity is
     * processed one tick after collision).</p>
     *
     * <p>Best for: precise close-range melee hits (≤5 blocks) and projectile
     * impacts — the projectile entity's position is at the AABB entry surface
     * at the moment of damage, so {@code tMin ≈ 0} and the check passes.
     * <em>Do not use for long-range ranged attacks</em> (sniping, bow shots
     * from 10+ blocks) — the attacker eye is too far away and the result
     * always falls back to the centre position.</p>
     */
    IMPACT {
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
     * <p>The indicator spawns at the attacker's eye position rather than at
     * the victim.  {@code pitch}, {@code yaw}, and all victim parameters are
     * ignored.</p>
     *
     * <p>Best for: healing numbers, self-buff floaters, combo counters, thorns
     * / reflect damage, or any effect that should visually originate from the
     * damage source rather than appear on the victim.</p>
     */
    ORIGIN {
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
