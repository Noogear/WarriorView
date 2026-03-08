package cn.warriorview.animation.api;

/**
 * The type of animation.
 */
public enum AnimationType {
    /** A timeline-based animation defined by keyframes. Pre-baked at load time. */
    KEYFRAME,
    /** A math-equation-based animation. Baked at spawn time with a fixed random seed. */
    EQUATION,
    /** A preset library entry that wraps another animation definition. */
    PRESET
}
