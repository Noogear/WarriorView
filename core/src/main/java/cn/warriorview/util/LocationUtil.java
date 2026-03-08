package cn.warriorview.util;

import com.github.retrooper.packetevents.util.Vector3d;

public class LocationUtil {

    /**
     * 算法 1：点积视线投影法 (Dot-Product Gaze Projection)
     *
     * <p>将「攻击者眼睛 → 被攻击者身体中心」向量投影到攻击者标准化视线方向上，
     * 得到沿视线方向的距离 t，再向后退 (vW/2 + 0.3) 以贴合被攻击者近侧表面，
     * 最终在视线射线上的对应位置生成指示器。</p>
     *
     * <p>对于投射物命中：此时 attacker 实体是投射物本身（位置 ≈ AABB 入射面，
     * pitch/yaw = 飞行方向），投影后退量恰好将指示器置于入射面外侧，效果正确。</p>
     *
     * <p>适用：近战攻击、定向技能（攻击者朝向与受害者对齐时精度最高）。
     * 对于侧向击打（如 AoE 扫击），投影结果可能偏离实际接触点，但仍在合理范围内。</p>
     */
    public static Vector3d getProjectedHitLocation(double eyeX, double eyeY, double eyeZ, float aPitch, float aYaw,
            double vX, double vY, double vZ, double vH, double vW) {

        final double pitch = aPitch * 0.017453292519943295;
        final double yaw = aYaw * 0.017453292519943295;
        final double xz = Math.cos(pitch);

        final double dirX = -xz * Math.sin(yaw);
        final double dirY = -Math.sin(pitch);
        final double dirZ = xz * Math.cos(yaw);

        final double dx = vX - eyeX;
        final double dy = (vY + vH / 2.0) - eyeY;
        final double dz = vZ - eyeZ;

        double projectionDistance = dx * dirX + dy * dirY + dz * dirZ;

        // 防穿模拉回
        final double pushback = (vW / 2.0) + 0.3;
        projectionDistance -= pushback;

        final double targetX = eyeX + dirX * projectionDistance;
        final double targetY = eyeY + dirY * projectionDistance;
        final double targetZ = eyeZ + dirZ * projectionDistance;

        return new Vector3d(targetX, targetY, targetZ);
    }

    /**
     * 算法 2：AABB 膨胀钳制法 (Expanded AABB Clamping)
     *
     * <p>将攻击者眼睛坐标各轴独立钳制到被攻击者 AABB（四周各膨胀 0.3）内，
     * 即指示器出现在被攻击者体表离攻击者最近的点。无三角函数，计算最快。</p>
     *
     * <p>当 attacker 为 null 时会回退为 victim 本身，此时眼睛坐标在 victim 自身
     * AABB 内部，钳制结果即为 victim 的体内位置，非常适合无方向来源的持续伤害。</p>
     *
     * <p>适用：AoE 伤害、溅射、DoT 跳伤，以及任何不需要精确方向信息的伤害类型。</p>
     */
    public static Vector3d getClampedHitLocation(double eyeX, double eyeY, double eyeZ, double vX, double vY, double vZ,
            double vH, double vW) {

        final double halfWidth = vW / 2.0;

        final double padding = 0.3;

        final double minX = vX - halfWidth - padding;
        final double maxX = vX + halfWidth + padding;
        final double minY = vY - padding;
        final double maxY = vY + vH + padding;
        final double minZ = vZ - halfWidth - padding;
        final double maxZ = vZ + halfWidth + padding;

        // 几何钳制
        final double hitX = Math.max(minX, Math.min(eyeX, maxX));
        final double hitY = Math.max(minY, Math.min(eyeY, maxY));
        final double hitZ = Math.max(minZ, Math.min(eyeZ, maxZ));

        return new Vector3d(hitX, hitY, hitZ);
    }

    /**
     * 算法 3：射线检测法 (Liang-Barsky Ray-AABB Intersection)
     *
     * <p>从攻击者眼睛沿视线方向发射射线，计算与 AABB 的真实入射交点（tMin），
     * 再沿射线方向后退 0.3 以贴合表面。零 GC，方向精度最高。</p>
     *
     * <p><b>硬性距离限制：仅 5 格以内有效。</b>当 {@code tMin > 5.0}、{@code tMin < 0}
     * （眼睛已越过入射面）或射线未命中 AABB 时，均回退到保底坐标
     * {@code (vX, vY + vH × 0.75, vZ)}（被攻击者上半身中心）。</p>
     *
     * <p>回退触发的三种情况：(1) 射线完全未穿过 AABB；(2) 攻击者距入射点超过 5 格；
     * (3) tMin < 0，即攻击者眼睛已在入射面后侧（常见于投射物在碰撞后一 tick 才
     * 触发伤害事件的情况）。</p>
     *
     * <p>适用：精确近战命中（≤5 格）和投射物撞击（投射物实体位置恰在 AABB
     * 入射面处，tMin ≈ 0，条件满足）。<b>不适用于远程弓箭/狙击等长距离攻击</b>——
     * 攻击者眼睛与被攻击者距离超过 5 格时将始终回退到保底坐标。</p>
     */
    public static Vector3d getRayTracedHitLocation(double eyeX, double eyeY, double eyeZ, float aPitch, float aYaw,
            double vX, double vY, double vZ, double vH, double vW) {
        // 计算方向向量
        final double pitch = aPitch * 0.017453292519943295;
        final double yaw = aYaw * 0.017453292519943295;
        final double xz = Math.cos(pitch);
        final double dirX = -xz * Math.sin(yaw);
        final double dirY = -Math.sin(pitch);
        final double dirZ = xz * Math.cos(yaw);

        // 获取并构建目标 AABB
        final double hWidth = vW / 2.0;

        // 实体边界
        final double minX = vX - hWidth;
        final double maxX = vX + hWidth;
        final double minY = vY;
        final double maxY = vY + vH;
        final double minZ = vZ - hWidth;
        final double maxZ = vZ + hWidth;

        // 预计算方向倒数优化性能
        final double invX = (Math.abs(dirX) > 1.0E-5) ? 1.0 / dirX : Double.POSITIVE_INFINITY;
        final double invY = (Math.abs(dirY) > 1.0E-5) ? 1.0 / dirY : Double.POSITIVE_INFINITY;
        final double invZ = (Math.abs(dirZ) > 1.0E-5) ? 1.0 / dirZ : Double.POSITIVE_INFINITY;

        // 计算射线与六面相交情况
        final double t1 = (minX - eyeX) * invX;
        final double t2 = (maxX - eyeX) * invX;
        final double t3 = (minY - eyeY) * invY;
        final double t4 = (maxY - eyeY) * invY;
        final double t5 = (minZ - eyeZ) * invZ;
        final double t6 = (maxZ - eyeZ) * invZ;

        // 取进入点最大值与退出点最小值
        final double tMin = Math.max(Math.max(Math.min(t1, t2), Math.min(t3, t4)), Math.min(t5, t6));
        final double tMax = Math.min(Math.min(Math.max(t1, t2), Math.max(t3, t4)), Math.max(t5, t6));

        // 判定：穿过盒子且在前方 5 格内
        if (tMax >= tMin && tMin >= 0 && tMin <= 5.0) {
            // 计算击中点并轻微后退防穿模
            return new Vector3d(
                    eyeX + dirX * tMin - dirX * 0.3,
                    eyeY + dirY * tMin - dirY * 0.3,
                    eyeZ + dirZ * tMin - dirZ * 0.3);
        }

        // 未击中时返回保底坐标
        return new Vector3d(vX, vY + vH * 0.75, vZ);
    }
}