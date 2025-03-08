package cn.warriorView.object.animation.type;

import cn.warriorView.object.animation.AnimationParams;
import cn.warriorView.object.animation.BaseAnimation;
import com.github.retrooper.packetevents.util.Vector3d;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.Set;
import java.util.function.Consumer;

public class Slant extends BaseAnimation {
    private static final Vector UP = new Vector(0, 1, 0);

    public Slant(AnimationParams params) {
        super(params);
    }

    @Override
    protected Vector processDirection(Vector original) {
        return original.clone().add(UP).normalize();
    }

    @Override
    protected BaseUpdater createUpdater(int entityId, Vector3d location, Vector direction, Set<Player> players, Consumer<Vector3d> onComplete) {
        return new SlantUpdater(entityId, location, direction, players, onComplete);
    }

    private class SlantUpdater extends BaseUpdater {
        private final double x;
        private final double z;
        private final double y;

        public SlantUpdater(int entityId, Vector3d location, Vector direction, Set<Player> players, Consumer<Vector3d> onComplete) {
            super(entityId, location, players, onComplete);
            this.y = direction.getY();
            if (isRotation) {
                double[] rotated = rotate(direction.getX(), direction.getZ());
                this.x = rotated[0];
                this.z = rotated[1];
            } else {
                this.x = direction.getX();
                this.z = direction.getZ();
            }
        }

        @Override
        protected void updatePosition() {
            teleportPacket.setPosition(initialLocation.add(x * distance, y * move, z * distance));
        }

        private double[] rotate(double x0, double z0) {
            return new double[]{x0 * cosCache + z0 * sinCache, z0 * cosCache - x0 * sinCache};
        }
    }
}
