package cn.warriorView.object.animation;

import cn.warriorView.object.animation.type.AnimationGroup;

import java.util.List;

public class AnimationFactory {

    public static IAnimation getGroup(List<IAnimation> animations) {
        if (animations.size() == 1) {
            return animations.getFirst();
        }
        AnimationGroup container = new AnimationGroup();
        for (IAnimation animation : animations) {
            container.addAnimation(animation);
        }
        return container;
    }

}
