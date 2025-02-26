package cn.warriorView.Object.Animation;

import cn.warriorView.Object.Animation.Type.AnimationGroup;

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
