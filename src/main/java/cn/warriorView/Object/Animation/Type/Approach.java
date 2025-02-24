package cn.warriorView.Object.Animation.Type;

import cn.warriorView.Object.Animation.AnimationParams;
import cn.warriorView.Object.Animation.IAnimation;
import com.github.retrooper.packetevents.util.Vector3d;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.Set;
import java.util.function.Consumer;

public class Approach implements IAnimation {


    public Approach(AnimationParams params) {
    }

    @Override
    public void play(int entityId, Vector3d location, Vector unitVec, Set<Player> players, Consumer<Vector3d> onComplete) {

    }
}
