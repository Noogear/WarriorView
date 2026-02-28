package cn.warriorview.util;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.joml.Vector3d;

public class LocationUtil {

    public static Vector3d getHitLocation(Location attackerEyeLoc, Entity victim) {
        // 1. 提取玩家眼睛坐标
        double ax = attackerEyeLoc.getX();
        double ay = attackerEyeLoc.getY();
        double az = attackerEyeLoc.getZ();

        // 2. 将角度转换为弧度 (直接乘常量，0.017453292519943295 = Math.PI / 180.0)
        double pitch = attackerEyeLoc.getPitch() * 0.017453292519943295;
        double yaw = attackerEyeLoc.getYaw() * 0.017453292519943295;
        double xz = Math.cos(pitch);

        // 3. 计算玩家视线的单位方向向量
        double dirX = -xz * Math.sin(yaw);
        double dirY = -Math.sin(pitch);
        double dirZ = xz * Math.cos(yaw);

        // 4. 获取怪物真实中心点
        Location vLoc = victim.getLocation();
        double dx = vLoc.getX() - ax;
        double dy = (vLoc.getY() + victim.getHeight() / 2.0) - ay; // 加上身高的一半，取胸口中心
        double dz = vLoc.getZ() - az;

        // 5. 点积计算：求出怪物中心点在玩家视线上的精确“投影长度”
        double projectionDistance = dx * dirX + dy * dirY + dz * dirZ;

        // 6. 拉回距离：减去怪物的半宽，再多退 0.3 格空气
        double pushback = (victim.getWidth() / 2.0) + 0.3;
        projectionDistance -= pushback;

        // 7. 沿视线方向推进最终的长度，得出绝对击中坐标
        double targetX = ax + dirX * projectionDistance;
        double targetY = ay + dirY * projectionDistance;
        double targetZ = az + dirZ * projectionDistance;

        return new Vector3d(targetX, targetY, targetZ);
    }

    public static Vector3d getClampLocation(Location attackerEyeLoc, Entity victim) {
        // 1. 提取玩家眼睛坐标 (基础 double 类型)
        double eyeX = attackerEyeLoc.getX();
        double eyeY = attackerEyeLoc.getY();
        double eyeZ = attackerEyeLoc.getZ();

        // 2. 获取怪物中心坐标与体型尺寸 (取代 getBoundingBox)
        Location vLoc = victim.getLocation();
        double vX = vLoc.getX();
        double vY = vLoc.getY();
        double vZ = vLoc.getZ();

        double halfWidth = victim.getWidth() / 2.0;
        double height = victim.getHeight();

        // 3. 设定防穿模膨胀量
        double padding = 0.3;

        // 4. 手动计算并融合膨胀量后的 AABB 绝对边界
        // 这样可以提前把 padding 算进去，减少后面的加减法次数
        double minX = vX - halfWidth - padding;
        double maxX = vX + halfWidth + padding;
        double minY = vY - padding;
        double maxY = vY + height + padding;
        double minZ = vZ - halfWidth - padding;
        double maxZ = vZ + halfWidth + padding;

        // 5. O(1) 纯硬件级指令钳制 (Clamp)
        // 找寻膨胀箱表面距离玩家眼睛最近的 X, Y, Z 点
        double hitX = Math.max(minX, Math.min(eyeX, maxX));
        double hitY = Math.max(minY, Math.min(eyeY, maxY));
        double hitZ = Math.max(minZ, Math.min(eyeZ, maxZ));

        // 6. 直接封装返回
        return new Vector3d(hitX, hitY, hitZ);
    }

    public static Vector3d getRayTraceLocation(Location attackerEyeLoc, Entity victim) {
        // 1. 提取玩家眼睛坐标 (0 GC)
        double startX = attackerEyeLoc.getX();
        double startY = attackerEyeLoc.getY();
        double startZ = attackerEyeLoc.getZ();

        // 2. 从 Pitch/Yaw 计算归一化方向向量 (0 GC)
        double pitch = Math.toRadians(attackerEyeLoc.getPitch());
        double yaw = Math.toRadians(attackerEyeLoc.getYaw());
        double xz = Math.cos(pitch);
        double dirX = -xz * Math.sin(yaw);
        double dirY = -Math.sin(pitch);
        double dirZ = xz * Math.cos(yaw);

        // 3. 计算怪物的物理碰撞箱边界 (无需 victim.getBoundingBox()，0 GC)
        Location vLoc = victim.getLocation();
        double vX = vLoc.getX();
        double vY = vLoc.getY();
        double vZ = vLoc.getZ();
        double halfWidth = victim.getWidth() / 2.0;
        double height = victim.getHeight();

        double minX = vX - halfWidth;
        double maxX = vX + halfWidth;
        double minY = vY;
        double maxY = vY + height;
        double minZ = vZ - halfWidth;
        double maxZ = vZ + halfWidth;

        // 4. Liang-Barsky 射线与 AABB 碰撞检测核心算法
        double tMin = 0.0;
        double tMax = 5.0; // 设定最大攻击距离为 5.0 格

        // 检查 X 轴面
        if (Math.abs(dirX) < 1.0E-5) {
            if (startX < minX || startX > maxX)
                return getFallback(vX, vY, vZ, height);
        } else {
            double t1 = (minX - startX) / dirX;
            double t2 = (maxX - startX) / dirX;
            tMin = Math.max(tMin, Math.min(t1, t2));
            tMax = Math.min(tMax, Math.max(t1, t2));
        }

        // 检查 Y 轴面
        if (Math.abs(dirY) < 1.0E-5) {
            if (startY < minY || startY > maxY)
                return getFallback(vX, vY, vZ, height);
        } else {
            double t1 = (minY - startY) / dirY;
            double t2 = (maxY - startY) / dirY;
            tMin = Math.max(tMin, Math.min(t1, t2));
            tMax = Math.min(tMax, Math.max(t1, t2));
        }

        // 检查 Z 轴面
        if (Math.abs(dirZ) < 1.0E-5) {
            if (startZ < minZ || startZ > maxZ)
                return getFallback(vX, vY, vZ, height);
        } else {
            double t1 = (minZ - startZ) / dirZ;
            double t2 = (maxZ - startZ) / dirZ;
            tMin = Math.max(tMin, Math.min(t1, t2));
            tMax = Math.min(tMax, Math.max(t1, t2));
        }

        // 5. 判断是否击中 (tMax >= tMin 代表射线与方块有实际交集)
        if (tMax >= tMin && tMin <= 5.0) {
            // 计算精确击中点
            double hitX = startX + dirX * tMin;
            double hitY = startY + dirY * tMin;
            double hitZ = startZ + dirZ * tMin;

            // 防穿模：向视线反方向拉回 0.3 格
            hitX -= dirX * 0.3;
            hitY -= dirY * 0.3;
            hitZ -= dirZ * 0.3;

            return new Vector3d(hitX, hitY, hitZ);
        }

        // 没有击中时返回保底坐标
        return getFallback(vX, vY, vZ, height);
    }

    // 提取保底坐标方法以保持代码整洁
    private static Vector3d getFallback(double vX, double vY, double vZ, double height) {
        return new Vector3d(vX, vY + height * 0.75, vZ);
    }

}
