package cn.warriorView.Object.Animation.Type;

import cn.warriorView.Object.Animation.AnimationParams;
import cn.warriorView.Object.Animation.IAnimation;
import com.github.retrooper.packetevents.util.Vector3d;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Set;

public class ApproachAndSide implements IAnimation {
    public ApproachAndSide(AnimationParams animationParams, AnimationParams animationParams1, byte moveCount, long delay) {
    }


    @Override
    public Vector3d offset(Location location) {
        return null;
    }

    @Override
    public void play(int entityId, Vector3d location, Set<Player> players) {

    }
}
