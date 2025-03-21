package cn.warriorView.object.animation;

import cn.warriorView.util.PacketUtil;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.function.Consumer;

public class AnimationGroup implements IAnimation {
    private final List<IAnimation> animationCache = new ArrayList<>();

    public void addAnimation(IAnimation anim) {
        animationCache.add(anim);
    }

    @Override
    public void play(int entityId, Vector3d location, Vector direction,
                     Set<Player> viewers, Consumer<Vector3d> callback) {
        PlayContext ctx = new PlayContext(
                entityId,
                location,
                direction,
                viewers,
                callback,
                new LinkedList<>(animationCache)
        );
        ctx.startPlayback();
    }

    private static class PlayContext {
        final int entityId;
        final Vector direction;
        final Set<Player> viewers;
        final Queue<IAnimation> animationQueue;
        Vector3d currentPosition;

        PlayContext(int eid, Vector3d pos, Vector dir,
                    Set<Player> pls, Consumer<Vector3d> cb,
                    Queue<IAnimation> queue) {
            entityId = eid;
            currentPosition = pos;
            direction = dir;
            viewers = pls;
            animationQueue = queue;
        }

        void startPlayback() {
            playNext();
        }

        private void playNext() {
            if (animationQueue.isEmpty()) {
                PacketUtil.sendPacketToPlayers(new WrapperPlayServerDestroyEntities(entityId), viewers);
                viewers.clear();
                return;
            }
            IAnimation next = animationQueue.poll();
            next.play(entityId, currentPosition, direction, viewers, newPos -> {
                currentPosition = newPos;
                playNext();
            });
        }
    }
}