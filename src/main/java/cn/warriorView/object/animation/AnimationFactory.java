package cn.warriorView.object.animation;

import cn.warriorView.object.animation.type.AnimationGroup;
import cn.warriorView.util.PacketUtil;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;

import java.util.List;

public class AnimationFactory {

    public static IAnimation create(List<IAnimation> animations) {
        if (animations.size() == 1) {
            IAnimation single = animations.getFirst();
            return (entityId, location, direction, viewers, callback) -> single.play(entityId, location, direction, viewers, newPos -> {
                PacketUtil.sendPacketToPlayers(
                        new WrapperPlayServerDestroyEntities(entityId), viewers);
                viewers.clear();
                if (callback != null) callback.accept(newPos);
            });
        } else {
            AnimationGroup container = new AnimationGroup();
            for (IAnimation animation : animations) {
                container.addAnimation(animation);
            }
            return container;
        }
    }
}
