package cn.warriorview.util;

import org.bukkit.Location;
import org.joml.Vector3d;

public class LocationUtil {

    /**
     * 算法 1：点积视线投影法 (Dot Product Projection)
     * 利用向量点积计算目标视线投影点，结合体型防穿模。
     * 适用：常规技能与弹道法术。
     */
    public static Vector3d getProjectedHitLocation(Location attackerEyeLoc, Location victimLoc, double victimH,
            double victimW) {
        final double ax = attackerEyeLoc.getX();
        final double ay = attackerEyeLoc.getY();
        final double az = attackerEyeLoc.getZ();

        final double pitch = attackerEyeLoc.getPitch() * 0.017453292519943295;
        final double yaw = attackerEyeLoc.getYaw() * 0.017453292519943295;
        final double xz = Math.cos(pitch);

        final double dirX = -xz * Math.sin(yaw);
        final double dirY = -Math.sin(pitch);
        final double dirZ = xz * Math.cos(yaw);

        final double dx = victimLoc.getX() - ax;
        final double dy = (victimLoc.getY() + victimH / 2.0) - ay;
        final double dz = victimLoc.getZ() - az;

        double projectionDistance = dx * dirX + dy * dirY + dz * dirZ;

        // 防穿模拉回
        final double pushback = (victimW / 2.0) + 0.3;
        projectionDistance -= pushback;

        final double targetX = ax + dirX * projectionDistance;
        final double targetY = ay + dirY * projectionDistance;
        final double targetZ = az + dirZ * projectionDistance;

        return new Vector3d(targetX, targetY, targetZ);
    }

    /**
     * 算法 2：AABB 膨胀钳制法 (Expanded AABB Clamping)
     * 无开方和三角函数运算，计算极快。
     * 适用：AoE 及持续性跳伤等无需精准追踪的技能。
     */
    public static Vector3d getClampedHitLocation(Location attackerEyeLoc, Location victimLoc, double victimH,
            double victimW) {
        final double eyeX = attackerEyeLoc.getX();
        final double eyeY = attackerEyeLoc.getY();
        final double eyeZ = attackerEyeLoc.getZ();

        final double vX = victimLoc.getX();
        final double vY = victimLoc.getY();
        final double vZ = victimLoc.getZ();

        final double halfWidth = victimW / 2.0;
        final double height = victimH;

        final double padding = 0.3;

        final double minX = vX - halfWidth - padding;
        final double maxX = vX + halfWidth + padding;
        final double minY = vY - padding;
        final double maxY = vY + height + padding;
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
     * 计算视线与碰撞箱的真实交点，零 GC，精确度高。
     * 适用：硬核单体技能，如狙击、爆头判定等。
     */
    public static Vector3d getRayTracedHitLocation(Location attackerEyeLoc, Location victimLoc, double victimH,
            double victimW) {
        // 初始化攻击者数据
        final double startX = attackerEyeLoc.getX();
        final double startY = attackerEyeLoc.getY();
        final double startZ = attackerEyeLoc.getZ();

        // 计算方向向量
        final double pitch = attackerEyeLoc.getPitch() * 0.017453292519943295;
        final double yaw = attackerEyeLoc.getYaw() * 0.017453292519943295;
        final double xz = Math.cos(pitch);
        final double dirX = -xz * Math.sin(yaw);
        final double dirY = -Math.sin(pitch);
        final double dirZ = xz * Math.cos(yaw);

        // 获取并构建目标 AABB
        final double vX = victimLoc.getX();
        final double vY = victimLoc.getY();
        final double vZ = victimLoc.getZ();
        final double hWidth = victimW / 2.0;
        final double height = victimH;

        // 实体边界
        final double minX = vX - hWidth;
        final double maxX = vX + hWidth;
        final double minY = vY;
        final double maxY = vY + height;
        final double minZ = vZ - hWidth;
        final double maxZ = vZ + hWidth;

        // 预计算方向倒数优化性能
        final double invX = (Math.abs(dirX) > 1.0E-5) ? 1.0 / dirX : Double.POSITIVE_INFINITY;
        final double invY = (Math.abs(dirY) > 1.0E-5) ? 1.0 / dirY : Double.POSITIVE_INFINITY;
        final double invZ = (Math.abs(dirZ) > 1.0E-5) ? 1.0 / dirZ : Double.POSITIVE_INFINITY;

        // 计算射线与六面相交情况
        final double t1 = (minX - startX) * invX;
        final double t2 = (maxX - startX) * invX;
        final double t3 = (minY - startY) * invY;
        final double t4 = (maxY - startY) * invY;
        final double t5 = (minZ - startZ) * invZ;
        final double t6 = (maxZ - startZ) * invZ;

        // 取进入点最大值与退出点最小值
        final double tMin = Math.max(Math.max(Math.min(t1, t2), Math.min(t3, t4)), Math.min(t5, t6));
        final double tMax = Math.min(Math.min(Math.max(t1, t2), Math.max(t3, t4)), Math.max(t5, t6));

        // 判定：穿过盒子且在前方 5 格内
        if (tMax >= tMin && tMin >= 0 && tMin <= 5.0) {
            // 计算击中点并轻微后退防穿模
            return new Vector3d(
                    startX + dirX * tMin - dirX * 0.3,
                    startY + dirY * tMin - dirY * 0.3,
                    startZ + dirZ * tMin - dirZ * 0.3);
        }

        // 未击中时返回保底坐标
        return new Vector3d(vX, vY + height * 0.75, vZ);
    }
}