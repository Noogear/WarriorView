package cn.warriorview.animation.definition;

import cn.warriorview.animation.api.AnimationType;
import cn.warriorview.animation.api.Space;
import cn.warriorview.animation.data.DisplaySettings;

/**
 * A preset library entry.
 *
 * <p>A preset wraps another {@link AnimationDef} and may override its
 * {@link DisplaySettings}.  Presets are resolved at load time so the
 * {@code resolved} field is never {@code null} at runtime.</p>
 *
 * @param name      unique preset identifier (often shared across files)
 * @param settings  display settings for this preset (may differ from the delegate)
 * @param resolved  the concrete animation definition this preset delegates to
 */
public record PresetDef(
        String         name,
        DisplaySettings settings,
        AnimationDef   resolved
) implements AnimationDef {

    @Override
    public AnimationType type() {
        return AnimationType.PRESET;
    }

    @Override
    public Space space() {
        return resolved.space();
    }

    @Override
    public int totalDurationTicks() {
        return resolved.totalDurationTicks();
    }

    /** 委托给 {@link #resolved()} 定义进行烘焙。 */
    @Override
    public cn.warriorview.animation.data.BakedSequence bake(double r) {
        return resolved.bake(r);
    }
}
