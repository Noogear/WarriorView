package cn.warriorview.animation.api;

import com.github.retrooper.packetevents.util.Vector3d;

/**
 * Determines the initial spawn position of a TextDisplay damage indicator.
 *
 * <p>Each constant wraps one positioning strategy.  Call {@link #resolve} with
 * all source/target parameters; unused parameters are simply ignored by the
 * variant.</p>
 *
 * <h3>Quick selection guide</h3>
 * <pre>
 *  AIM           – melee / directed attacks        (gaze-depth projection, ~6 trig ops)
 *  SURFACE       – AoE / DoT / direction-agnostic  (AABB clamp, no trig, fastest)
 *  IMPACT        – precise melee / projectile hit  (ray-AABB, hard ≤5-block limit)
 *  SOURCE_ORIGIN – self-damage / healing / buffs   (trivial, returns source eye)
 *  TARGET_EYE    – target eye-level spawn          (trivial, returns target eye Y)
 *  TARGET_BOTTOM – target foot-level spawn         (trivial, returns target foot Y)
 * </pre>
 *
 * <p><b>Note on projectile attacks:</b> when a projectile entity deals damage,
 * the {@code source} passed to {@link #resolve} is the projectile entity itself
 * (position = impact point, pitch/yaw = flight direction at moment of collision).
 * All strategies handle this correctly, but {@link #IMPACT} is the most accurate
 * since the projectile is right at the victim's AABB entry surface ({@code tMin ≈ 0}).</p>
 */
public enum IndicatorSpawnStrategy {

    /**
     * <b>Algorithm 1 — Dot-Product Gaze Projection</b>
     *
     * <p>Scalar-projects the source-to-target vector onto the normalised gaze
     * direction, then places the indicator at that depth along the gaze ray,
     * pulled back by {@code tW / 2 + 0.3} to sit just outside the target's
     * near surface as seen from the source.</p>
     *
     * <p>For projectile hits the "source" is the projectile entity itself
     * (position ≈ AABB entry surface, direction = flight direction), so the
     * projection naturally places the indicator just outside the impact face.</p>
     *
     * <p>Best for: melee attacks and any directed hit where the source is
     * facing the target.  Degrades gracefully for off-angle hits (e.g. AoE
     * side-swipe) but is not the most accurate in those cases.</p>
     */
    AIM {
        @Override
        public Vector3d resolve(double sX, double sY, double sZ,
                                double sEyeY, double tEyeY,
                                float pitch, float yaw,
                                double tX, double tY, double tZ,
                                double tH, double tW) {
            return getProjectedHitLocation(
                    sX, sEyeY, sZ, pitch, yaw, tX, tY, tZ, tH, tW);
        }
    },

    /**
     * <b>Algorithm 2 — Expanded AABB Clamping</b>
     *
     * <p>Clamps the source's eye position to the target's bounding box
     * expanded by {@code 0.3} on every side.  The indicator appears at the
     * nearest point on the target's body surface to the source.  Uses no
     * trigonometry; fastest of all variants.</p>
     *
     * <p>When the source is {@code null} (e.g. DoT with no source), it
     * defaults to the target entity, so the eye position lies within the
     * target's own AABB — the indicator then appears at the target's body
     * centre area, which is ideal for directionless damage ticks.</p>
     *
     * <p>Best for: AoE damage, splash, persistent DoT, and any damage whose
     * source direction is irrelevant or unknown.</p>
     */
    SURFACE {
        @Override
        public Vector3d resolve(double sX, double sY, double sZ,
                                double sEyeY, double tEyeY,
                                float pitch, float yaw,
                                double tX, double tY, double tZ,
                                double tH, double tW) {
            return getClampedHitLocation(
                    sX, sEyeY, sZ, tX, tY, tZ, tH, tW);
        }
    },

    /**
     * <b>Algorithm 3 — Liang-Barsky Ray-AABB Intersection</b>
     *
     * <p>Casts a ray from the source's eye along their look direction and
     * finds the exact AABB entry point, then pulls back {@code 0.3} in the
     * ray direction to sit on the surface.  Zero GC; highest directional
     * accuracy of all variants.</p>
     *
     * <p><b>Hard range limit: 5 blocks.</b>  The intersection is rejected when
     * {@code tMin > 5.0} or {@code tMin < 0} (source's eye has already
     * passed the entry surface).  In those cases the method falls back to
     * {@code (tX, tY + tH × 0.75, tZ)} — the target's upper-body centre.</p>
     *
     * <p>Fall-back triggers: (1) ray does not intersect the AABB, (2) nearest
     * intersection is more than 5 blocks away, (3) source eye is already
     * inside or past the entry face (common when a projectile entity is
     * processed one tick after collision).</p>
     *
     * <p>Best for: precise close-range melee hits (≤5 blocks) and projectile
     * impacts — the projectile entity's position is at the AABB entry surface
     * at the moment of damage, so {@code tMin ≈ 0} and the check passes.
     * <em>Do not use for long-range ranged attacks</em> (sniping, bow shots
     * from 10+ blocks) — the source eye is too far away and the result
     * always falls back to the centre position.</p>
     */
    IMPACT {
        @Override
        public Vector3d resolve(double sX, double sY, double sZ,
                                double sEyeY, double tEyeY,
                                float pitch, float yaw,
                                double tX, double tY, double tZ,
                                double tH, double tW) {
            return getRayTracedHitLocation(
                    sX, sEyeY, sZ, pitch, yaw, tX, tY, tZ, tH, tW);
        }
    },

    /**
     * <b>Attacker exact eye position</b>
     *
     * <p>The indicator spawns at the source's eye position rather than at
     * the target.  {@code pitch}, {@code yaw}, and all target parameters are
     * ignored.</p>
     *
     * <p>Best for: healing numbers, self-buff floaters, combo counters, thorns
     * / reflect damage, or any effect that should visually originate from the
     * damage source rather than appear on the victim.</p>
     */
    SOURCE_ORIGIN {
        @Override
        public Vector3d resolve(double sX, double sY, double sZ,
                                double sEyeY, double tEyeY,
                                float pitch, float yaw,
                                double tX, double tY, double tZ,
                                double tH, double tW) {
            return new Vector3d(sX, sEyeY, sZ);
        }
    },

    /**
     * <b>Target eye position</b>
     *
     * <p>The indicator spawns at the target's eye position.  All source
     * parameters are ignored.</p>
     *
     * <p>Best for: buffs or effects that should visually appear at the
     * recipient's eye level, such as healing numbers, status effects, or
     * player-targeted UI floaters.</p>
     */
    TARGET_EYE {
        @Override
        public Vector3d resolve(double sX, double sY, double sZ,
                                double sEyeY, double tEyeY,
                                float pitch, float yaw,
                                double tX, double tY, double tZ,
                                double tH, double tW) {
            return new Vector3d(tX, tEyeY, tZ);
        }
    },

    /**
     * <b>Target foot position</b>
     *
     * <p>The indicator spawns at the target's foot (bottom of AABB).  All
     * source parameters are ignored.</p>
     *
     * <p>Best for: ground-level effects or damage numbers that should appear
     * at the base of the entity, such as fall damage, trap damage, or
     * area markers.</p>
     */
    TARGET_BOTTOM {
        @Override
        public Vector3d resolve(double sX, double sY, double sZ,
                                double sEyeY, double tEyeY,
                                float pitch, float yaw,
                                double tX, double tY, double tZ,
                                double tH, double tW) {
            return new Vector3d(tX, tY, tZ);
        }
    };

    // ── Abstract contract ─────────────────────────────────────────────────────

    /**
     * Computes the world-space spawn coordinate for a TextDisplay entity.
     *
     * @param sX     source entity X (foot position; same as eye X)
     * @param sY     source entity Y (foot position)
     * @param sZ     source entity Z (foot position; same as eye Z)
     * @param sEyeY  source eye Y (= sY + eye height; equals sY for non-LivingEntity)
     * @param tEyeY  target eye Y (= tY + eye height; equals tY for non-LivingEntity)
     * @param pitch  source pitch in degrees
     * @param yaw    source yaw   in degrees
     * @param tX     target foot position X
     * @param tY     target foot position Y  (bottom of AABB)
     * @param tZ     target foot position Z
     * @param tH     target bounding-box height
     * @param tW     target bounding-box width (also used as depth)
     * @return the spawn position for the TextDisplay entity
     */
    public abstract Vector3d resolve(double sX, double sY, double sZ,
                                     double sEyeY, double tEyeY,
                                     float pitch, float yaw,
                                     double tX, double tY, double tZ,
                                     double tH, double tW);

    // ── Positioning algorithm implementations ─────────────────────────────

    /**
     * 算法 1：点积视线投影法 (Dot-Product Gaze Projection)
     *
     * <p>将来源眼睛 → 目标身体中心向量投影到来源标准化视线方向上，
     * 得到沿视线方向的距离 t，再向后退 (tW/2 + 0.3) 以贴合目标近侧表面，
     * 最终在视线射线上的对应位置生成指示器。</p>
     *
     * <p>适用：近战攻击、定向技能（来源朝向与目标对齐时精度最高）。</p>
     */
    private static Vector3d getProjectedHitLocation(double sX, double sEyeY, double sZ,
            float sPitch, float sYaw,
            double tX, double tY, double tZ, double tH, double tW) {

        final double pitch = sPitch * 0.017453292519943295;
        final double yaw = sYaw * 0.017453292519943295;
        final double xz = Math.cos(pitch);

        final double dirX = -xz * Math.sin(yaw);
        final double dirY = -Math.sin(pitch);
        final double dirZ = xz * Math.cos(yaw);

        final double dx = tX - sX;
        final double dy = (tY + tH / 2.0) - sEyeY;
        final double dz = tZ - sZ;

        double projectionDistance = dx * dirX + dy * dirY + dz * dirZ;

        // 防穿模拉回
        final double pushback = (tW / 2.0) + 0.3;
        projectionDistance -= pushback;

        final double targetX = sX + dirX * projectionDistance;
        final double targetY = sEyeY + dirY * projectionDistance;
        final double targetZ = sZ + dirZ * projectionDistance;

        return new Vector3d(targetX, targetY, targetZ);
    }

    /**
     * 算法 2：AABB 膨胀钳制法 (Expanded AABB Clamping)
     *
     * <p>将来源眼睛坐标各轴独立钳制到目标 AABB（四周各膨胀 0.3）内。
     * 无三角函数，计算最快。</p>
     *
     * <p>适用：AoE 伤害、溅射、DoT 跳伤，以及任何不需要精确方向信息的伤害类型。</p>
     */
    private static Vector3d getClampedHitLocation(double sX, double sEyeY, double sZ,
            double tX, double tY, double tZ, double tH, double tW) {

        final double halfWidth = tW / 2.0;
        final double padding = 0.3;

        final double minX = tX - halfWidth - padding;
        final double maxX = tX + halfWidth + padding;
        final double minY = tY - padding;
        final double maxY = tY + tH + padding;
        final double minZ = tZ - halfWidth - padding;
        final double maxZ = tZ + halfWidth + padding;

        // 几何钳制
        final double hitX = Math.max(minX, Math.min(sX, maxX));
        final double hitY = Math.max(minY, Math.min(sEyeY, maxY));
        final double hitZ = Math.max(minZ, Math.min(sZ, maxZ));

        return new Vector3d(hitX, hitY, hitZ);
    }

    /**
     * 算法 3：射线检测法 (Liang-Barsky Ray-AABB Intersection)
     *
     * <p>从来源眼睛沿视线方向发射射线，计算与 AABB 的真实入射交点（tMin），
     * 再沿射线方向后退 0.3 以贴合表面。零 GC，方向精度最高。</p>
     *
     * <p><b>硬性距离限制：仅 5 格以内有效。</b>当 {@code tMin > 5.0}、{@code tMin < 0}
     * 或射线未命中 AABB 时，均回退到保底坐标 {@code (tX, tY + tH × 0.75, tZ)}。</p>
     *
     * <p>适用：精确近战命中（≤5 格）和投射物撞击。</p>
     */
    private static Vector3d getRayTracedHitLocation(double sX, double sEyeY, double sZ,
            float sPitch, float sYaw,
            double tX, double tY, double tZ, double tH, double tW) {

        final double pitch = sPitch * 0.017453292519943295;
        final double yaw = sYaw * 0.017453292519943295;
        final double xz = Math.cos(pitch);
        final double dirX = -xz * Math.sin(yaw);
        final double dirY = -Math.sin(pitch);
        final double dirZ = xz * Math.cos(yaw);

        final double hWidth = tW / 2.0;

        final double minX = tX - hWidth;
        final double maxX = tX + hWidth;
        final double minY = tY;
        final double maxY = tY + tH;
        final double minZ = tZ - hWidth;
        final double maxZ = tZ + hWidth;

        final double invX = (Math.abs(dirX) > 1.0E-5) ? 1.0 / dirX : Double.POSITIVE_INFINITY;
        final double invY = (Math.abs(dirY) > 1.0E-5) ? 1.0 / dirY : Double.POSITIVE_INFINITY;
        final double invZ = (Math.abs(dirZ) > 1.0E-5) ? 1.0 / dirZ : Double.POSITIVE_INFINITY;

        final double t1 = (minX - sX) * invX;
        final double t2 = (maxX - sX) * invX;
        final double t3 = (minY - sEyeY) * invY;
        final double t4 = (maxY - sEyeY) * invY;
        final double t5 = (minZ - sZ) * invZ;
        final double t6 = (maxZ - sZ) * invZ;

        final double tMin = Math.max(Math.max(Math.min(t1, t2), Math.min(t3, t4)), Math.min(t5, t6));
        final double tMax = Math.min(Math.min(Math.max(t1, t2), Math.max(t3, t4)), Math.max(t5, t6));

        if (tMax >= tMin && tMin >= 0 && tMin <= 5.0) {
            return new Vector3d(
                    sX + dirX * tMin - dirX * 0.3,
                    sEyeY + dirY * tMin - dirY * 0.3,
                    sZ + dirZ * tMin - dirZ * 0.3);
        }

        return new Vector3d(tX, tY + tH * 0.75, tZ);
    }
}
