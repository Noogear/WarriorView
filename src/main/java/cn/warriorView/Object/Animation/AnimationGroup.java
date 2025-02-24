package cn.warriorView.Object.Animation;

import cn.warriorView.Util.PacketUtil;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.function.Consumer;

public class AnimationGroup implements IAnimation {
    private final Queue<IAnimation> animationQueue = new LinkedList<>();
    private Consumer<Vector3d> groupCompleteCallback;
    private Vector3d currentLocation;
    private Set<Player> currentPlayers;
    private int currentEntityId;
    private Vector currentUnitVec;

    public static IAnimation create(List<IAnimation> animations) {
        if (animations.isEmpty()) {
            throw new IllegalArgumentException("Animation list cannot be empty");
        }
        if (animations.size() == 1) {
            return animations.getFirst();
        }
        AnimationGroup container = new AnimationGroup();
        for (IAnimation animation : animations) {
            if (animation instanceof AnimationGroup) {
                container.animationQueue.addAll(((AnimationGroup) animation).animationQueue);
            } else {
                container.addAnimation(animation);
            }
        }
        return container;
    }

    public void addAnimation(IAnimation animation) {
        animationQueue.offer(animation);
    }

    @Override
    public void play(int entityId, Vector3d location, Vector unitVec, Set<Player> players, Consumer<Vector3d> onComplete) {
        this.currentEntityId = entityId;
        this.currentLocation = location;
        this.currentUnitVec = unitVec;
        this.currentPlayers = players;
        this.groupCompleteCallback = onComplete;
        playNextAnimation();
    }

    private void playNextAnimation() {
        if (animationQueue.isEmpty()) {
            if (groupCompleteCallback != null) {
                groupCompleteCallback.accept(currentLocation);
            }
            PacketUtil.sendPacketToPlayers(new WrapperPlayServerDestroyEntities(currentEntityId), currentPlayers);
            currentPlayers.clear();
            return;
        }

        IAnimation nextAnim = animationQueue.poll();
        nextAnim.play(currentEntityId, currentLocation, currentUnitVec, currentPlayers, newLocation -> {
            currentLocation = newLocation;
            playNextAnimation();
        });
    }
}