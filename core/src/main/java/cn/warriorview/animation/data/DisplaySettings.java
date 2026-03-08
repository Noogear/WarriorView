package cn.warriorview.animation.data;

import cn.warriorview.animation.api.Billboard;

/**
 * Immutable display settings that are shared across every frame of an animation.
 *
 * <h3>Display entity metadata (MC 1.21.x)</h3>
 * <pre>
 *  10 = teleport_duration    (int)   — 位置/旋转插值 tick 数
 *  15 = billboard            (byte)  — Billboard 约束
 *  16 = brightness_override  (int)   — 亮度覆盖 (blockLight &lt;&lt; 4 | skyLight &lt;&lt; 20)，-1 不覆盖
 *  17 = view_range           (float) — 客户端可见距离倍率
 *  18 = shadow_radius        (float) — 地面阴影半径
 *  19 = shadow_strength      (float) — 地面阴影强度
 *  22 = glow_color_override  (int)   — 发光颜色覆盖
 *  24 = line_width           (int)   — 文本最大行宽（像素）
 *  25 = background_color     (int)   — ARGB 背景色
 *  27 = style_flags          (byte)  — bit0=shadow, bit1=see-through
 * </pre>
 *
 * @param billboard       billboard constraint mode (default CENTER)
 * @param seeThrough      whether the entity renders through blocks (default false)
 * @param textShadow      whether the text has a drop-shadow (default false)
 * @param backgroundColor packed ARGB background colour (default 0x40000000)
 * @param viewRange       client-side visibility range multiplier (default 1.0)
 * @param teleportDuration position/rotation interpolation ticks (default 0)
 * @param brightness      packed brightness override (blockLight &lt;&lt; 4 | skyLight &lt;&lt; 20), -1 = use world light
 * @param shadowRadius    ground shadow radius (default 0)
 * @param shadowStrength  ground shadow strength/opacity (default 1.0)
 * @param glowColorOverride RGB glow outline colour, 0 = default white
 * @param lineWidth       max text line width in pixels (default 200)
 * @param offset          spawn-anchor offset expression, evaluated once per instance
 */
public record DisplaySettings(
        Billboard billboard,
        boolean   seeThrough,
        boolean   textShadow,
        int       backgroundColor,
        float     viewRange,
        int       teleportDuration,
        int       brightness,
        float     shadowRadius,
        float     shadowStrength,
        int       glowColorOverride,
        int       lineWidth,
        OffsetExpr offset
) {
    /** Minecraft default background colour for text displays: semi-transparent black. */
    public static final int DEFAULT_BACKGROUND = 0x40000000;

    public static final DisplaySettings DEFAULT = new DisplaySettings(
            Billboard.CENTER,
            false,
            false,
            DEFAULT_BACKGROUND,
            1.0f,
            0,
            -1,
            0f,
            1.0f,
            0,
            200,
            OffsetExpr.ZERO
    );
}
