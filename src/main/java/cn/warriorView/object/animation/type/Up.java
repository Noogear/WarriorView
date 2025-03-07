package cn.warriorView.object.animation.type;

import cn.warriorView.object.animation.AnimationParams;
import cn.warriorView.object.animation.BaseAnimation;
import com.github.retrooper.packetevents.util.Vector3d;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.Set;
import java.util.function.Consumer;

public class Up extends BaseAnimation {
    public Up(AnimationParams params) {
        super(params);
    }

    @Override
    protected Vector processDirection(Vector original) {
        return original.clone().setY(0).normalize();
    }

    @Override
    protected BaseUpdater createUpdater(int entityId, Vector3d location, Vector direction, Set<Player> players, Consumer<Vector3d> onComplete) {
        return new UpUpdater(entityId, location, direction, players, onComplete);
    }

    private class UpUpdater extends BaseUpdater {
        private final double[] rotated = new double[3];

        public UpUpdater(int entityId, Vector3d location, Vector direction, Set<Player> players, Consumer<Vector3d> onComplete) {
            super(entityId, location, players, onComplete);
            if (isRotation) rotate(direction.getX(), direction.getZ());
        }

        @Override
        protected void updatePosition() {
            if (isRotation) {
                teleportPacket.setPosition(initialLocation.add(rotated[0] * move, rotated[1] * move, rotated[2] * move));
            } else {
                teleportPacket.setPosition(initialLocation.withY(move));
            }
        }

        private void rotate(double x, double z) {
            rotated[0] = z * sinCache;
            rotated[1] = cosCache;
            rotated[2] = -x * sinCache;
        }
    }
}

