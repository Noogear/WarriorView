package cn.warriorView.View.Animation;

import cn.warriorView.Main;
import com.github.retrooper.packetevents.util.Vector3d;
import org.bukkit.entity.Player;

import java.util.Set;

public abstract class Animation {

    protected Main plugin;
    protected byte moveCount;
    protected float max;
    protected float speed;

    public Animation(Main main, byte moveCount, float max, float speed) {
        this.plugin = main;
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
