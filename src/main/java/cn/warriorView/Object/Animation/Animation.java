package cn.warriorView.Object.Animation;

import cn.warriorView.Object.Offset;
import com.github.retrooper.packetevents.util.Vector3d;
import org.bukkit.entity.Player;

import java.util.Set;

public abstract class Animation {

    private final Offset offset;
    protected byte moveCount;
    protected float max;
    protected float speed;
    protected long delay;

    public Animation(byte moveCount, float max, float speed, long delay, Offset offset) {
        this.moveCount = moveCount;
        this.max = max;
        this.speed = speed;
        this.delay = delay;
        this.offset = offset;
    }

    public abstract void play(int entityId, Vector3d location, Set<Player> players);

    public Offset offset() {
        return offset;
    }

    public enum type {
        UP,
        DOWN,
        UP_AND_DOWN
    }
}
