package cn.warriorView.Manager;

import cn.warriorView.Object.Animation.Animation;

import java.util.HashMap;
import java.util.Map;

public class AnimationManager {
    
    private final Map<String, Animation> animations;
    
    public AnimationManager() {
        this.animations = new HashMap<>();
    }

    public void load() {
        animations.clear();


    }

    public Animation getAnimation(String groupId) {
        return animations.get(groupId);
    }

}
