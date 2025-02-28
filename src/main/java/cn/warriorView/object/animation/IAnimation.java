package cn.warriorView.object.animation;

import com.github.retrooper.packetevents.util.Vector3d;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.function.Consumer;

public interface IAnimation {

    void play(int entityId, Vector3d location, Vector unitVec, List<Player> players, Consumer<Vector3d> onComplete);

}