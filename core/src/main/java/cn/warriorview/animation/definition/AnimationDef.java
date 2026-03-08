package cn.warriorview.animation.definition;

import cn.warriorview.animation.api.AnimationType;
import cn.warriorview.animation.data.BakedSequence;
import cn.warriorview.animation.data.DisplaySettings;

/**
 * Sealed interface representing a fully parsed and pre-baked animation definition.
 * <p>
 * The three permitted implementations are:
 * <ul>
 *   <li>{@link KeyframeDef}  – timeline animations (pre-baked at load time)</li>
 *   <li>{@link EquationDef}  – math-equation animations (baked at spawn time)</li>
 *   <li>{@link PresetDef}    – preset library entry that wraps another definition</li>
 * </ul>
 */
public sealed interface AnimationDef permits KeyframeDef, EquationDef, PresetDef {

    /** Unique identifier used to look this definition up from the registry. */
    String name();

    /** Shared display settings (billboard, bg-color, offset, …). */
    DisplaySettings settings();

    /** Discriminator tag for fast instanceof-free dispatch. */
    AnimationType type();

    /** Total duration of this animation in game ticks. */
    int totalDurationTicks();

    /**
     * 将此动画定义烘焙为可播放的帧序列。
     *
     * <p>对于 {@link KeyframeDef}，直接返回预烘焙的共享序列（零拷贝）；
     * 对于 {@link EquationDef}，按随机值 {@code r} 实例级烘焙（每次新分配）；
     * 对于 {@link PresetDef}，委托给其 {@link PresetDef#resolved()} 定义。</p>
     *
     * @param r 每实例随机值 [0, 1)；Keyframe 定义忽略此参数
     */
    BakedSequence bake(double r);
}
