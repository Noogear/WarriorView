package cn.warriorView.object;

import com.github.retrooper.packetevents.util.Vector3d;
import org.bukkit.Location;
import org.bukkit.util.Vector;

public class Offset {
    private final double up;
    private final double approach;

    public Offset(double up, double approach) {
        this.up = up;
        this.approach = approach;
    }

    public Vector3d getPosition(Location location, Vector vector) {
        return new Vector3d(location.getX() + vector.getX() * approach, location.getY() + up, location.getZ() + vector.getZ() * approach);
    }

    public Vector3d getPosition(Location location) {
        Vector vector = location.getDirection().normalize();
        return new Vector3d(location.getX() + vector.getX() * approach, location.getY() + up, location.getZ() + vector.getZ() * approach);
    }
}
