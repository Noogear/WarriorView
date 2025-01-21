package cn.warriorView.View.Animation;

import com.github.retrooper.packetevents.util.Vector3d;
import org.bukkit.entity.Player;

import java.util.Set;

public abstract class Animation {

    protected byte moveCount;
    protected float max;
    protected float speed;

    public Animation(byte moveCount, float max, float speed) {
        this.moveCount = moveCount;
        this.max = max;
        this.speed = speed;
    }

    public abstract void play(int entityId, Vector3d location, Set<Player> players);

    public enum type {
        UP,
        DOWN,
        UP_AND_DOWN
    }
}
