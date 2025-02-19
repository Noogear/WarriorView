package cn.warriorView.Object.Animation;

import com.github.retrooper.packetevents.util.Vector3d;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Set;

public interface IAnimation {

    Vector3d offset(Location location) ;

    void play(int entityId, Vector3d location, Set<Player> players);
}
