package cn.warriorview.animation.definition;

import cn.warriorview.animation.api.AnimationType;
import cn.warriorview.animation.api.Space;
import cn.warriorview.animation.data.BakedSequence;
import cn.warriorview.animation.data.DisplaySettings;

/**
 * A keyframe-based animation definition.
 *
 * <p>The entire {@link BakedSequence} is computed once at load time and shared
 * immutably across every concurrent playback instance, yielding zero per-play
 * allocation for the frame data itself.</p>
 *
 * @param name     unique animation identifier
 * @param settings shared display settings
 * @param baked    pre-baked sequence containing the ordered frame array
 */
public record KeyframeDef(
        String          name,
        DisplaySettings settings,
        Space           space,
        BakedSequence   baked
) implements AnimationDef {

    @Override
    public AnimationType type() {
        return AnimationType.KEYFRAME;
    }

    @Override
    public int totalDurationTicks() {
        return baked.totalTicks();
    }

    /** 直接返回预烘焙序列，零拷贝（所有播放实例共享同一 {@link cn.warriorview.animation.data.BakedSequence}）。 */
    @Override
    public cn.warriorview.animation.data.BakedSequence bake(double r) {
        return baked;
    }
}
