package cn.warriorView.Object.Animation;

import cn.warriorView.Object.Animation.Type.Down;
import cn.warriorView.Object.Animation.Type.Up;
import cn.warriorView.Object.Animation.Type.UpAndDown;
import com.github.retrooper.packetevents.util.Vector3d;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.Set;

public abstract class Animation {
    private final float max;
    private final float speed;
    private final Vector offset;
    private final byte moveCount;
    private final long delay;

    protected Animation(AnimationParams params, byte moveCount, long delay) {
        this.max = params.max();
        this.speed = params.speed();
        this.offset = params.offset();
        this.moveCount = moveCount;
        this.delay = delay;
    }

    public static Animation create(AnimationParams params, byte moveCount, long delay) {
        switch (params.type()) {
            case UP -> {
                return new Up(params, moveCount, delay);
            }
            case DOWN -> {
                return new Down(params, moveCount, delay);
            }
            default -> {
                return new UpAndDown(params, moveCount, delay);
            }
        }
    }

    public abstract void play(int entityId, Vector3d location, Set<Player> players);

    public Vector offset() {
        return offset;
    }

    public byte moveCount() {
        return moveCount;
    }

    public float max() {
        return max;
    }

    public float speed() {
        return speed;
    }

    public long delay() {
        return delay;
    }

    public enum type {
        UP_AND_DOWN,
        UP,
        DOWN
    }
}
