package cn.warriorView.object.animation.type;

import cn.warriorView.object.animation.IAnimation;
import cn.warriorView.util.PacketUtil;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.function.Consumer;

public class AnimationGroup implements IAnimation {
    private final List<IAnimation> animationCache = new ArrayList<>();

    public void addAnimation(IAnimation anim) {
        animationCache.add(anim);
    }

    @Override
    public void play(int entityId, Vector3d location, Vector direction,
                     List<Player> viewers, Consumer<Vector3d> callback) {
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
        final List<Player> viewers;
        final Queue<IAnimation> animationQueue;
        Vector3d currentPosition;

        PlayContext(int eid, Vector3d pos, Vector dir,
                    List<Player> pls, Consumer<Vector3d> cb,
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