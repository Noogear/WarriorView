package cn.warriorView.object.animation.type;

import cn.warriorView.object.animation.IAnimation;
import cn.warriorView.util.PacketUtil;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.LinkedList;
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