package cn.warriorview.animation.api;

/**
 * 动画坐标空间。
 *
 * <ul>
 *   <li>{@link #WORLD} — 世界坐标系（默认）：tx/tz 直接映射到世界 X/Z 轴</li>
 *   <li>{@link #VIEW}  — 视角坐标系：tx=右，tz=前方（相对攻击者朝向），bake 后旋转到世界坐标</li>
 * </ul>
 */
public enum Space {
    WORLD,
    VIEW;

    public static Space fromName(String name) {
        if (name == null) return WORLD;
        return switch (name.toLowerCase()) {
            case "view", "local", "relative" -> VIEW;
            default -> WORLD;
        };
    }
}
