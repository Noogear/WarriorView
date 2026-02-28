package cn.warriorview.util;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.util.BoundingBox;
import org.joml.Vector3d;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

public class LocationUtil {

    public static Vector3d getHitLocation(Location attackerEyeLoc, Location entityEyeLoc) {
        double ax = attackerEyeLoc.getX();
        double ay = attackerEyeLoc.getY();
        double az = attackerEyeLoc.getZ();

        double dx = entityEyeLoc.getX() - ax;
        double dy = entityEyeLoc.getY() - ay;
        double dz = entityEyeLoc.getZ() - az;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);

        double pitch = Math.toRadians(attackerEyeLoc.getPitch());
        double yaw = Math.toRadians(attackerEyeLoc.getYaw());
        double xz = Math.cos(pitch);

        double dirX = -xz * Math.sin(yaw);
        double dirY = -Math.sin(pitch);
        double dirZ = xz * Math.cos(yaw);

        double targetX = ax + dirX * distance;
        double targetY = ay + dirY * distance;
        double targetZ = az + dirZ * distance;

        return new Vector3d(targetX, targetY, targetZ);
    }

    public Vector3d getClampLocation(Location attackerEyeLoc, Entity victim) {
        double eyeX = attackerEyeLoc.getX();
        double eyeY = attackerEyeLoc.getY();
        double eyeZ = attackerEyeLoc.getZ();

        BoundingBox box = victim.getBoundingBox();
        double padding = 0.3;

        double hitX = Math.max(box.getMinX() - padding, Math.min(eyeX, box.getMaxX() + padding));
        double hitY = Math.max(box.getMinY() - padding, Math.min(eyeY, box.getMaxY() + padding));
        double hitZ = Math.max(box.getMinZ() - padding, Math.min(eyeZ, box.getMaxZ() + padding));

        return new Vector3d(hitX, hitY, hitZ);
    }

    public Vector3d getRayTraceLocation(Location attackerEyeLoc, Entity victim) {
        Vector dir = attackerEyeLoc.getDirection();
        BoundingBox box = victim.getBoundingBox();

        RayTraceResult result = box.rayTrace(attackerEyeLoc.toVector(), dir, 5.0);

        double hitX, hitY, hitZ;

        if (result != null && result.getHitPosition() != null) {
            Vector hitPoint = result.getHitPosition();

            hitX = hitPoint.getX() - dir.getX() * 0.3;
            hitY = hitPoint.getY() - dir.getY() * 0.3;
            hitZ = hitPoint.getZ() - dir.getZ() * 0.3;
        } else {
            Location vLoc = victim.getLocation();
            hitX = vLoc.getX();
            hitY = vLoc.getY() + victim.getHeight() * 0.75;
            hitZ = vLoc.getZ();
        }

        return new Vector3d(hitX, hitY, hitZ);
    }

}
