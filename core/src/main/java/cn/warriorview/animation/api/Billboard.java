package cn.warriorview.animation.api;

/**
 * Billboard constraint modes for display entities.
 * Protocol IDs match the vanilla TextDisplay metadata field.
 */
public enum Billboard {
    /** No billboard – entity is fixed in world space. */
    FIXED(0),
    /** Entity rotates to face the viewer vertically only. */
    VERTICAL(1),
    /** Entity rotates to face the viewer horizontally only. */
    HORIZONTAL(2),
    /** Entity always faces the viewer on all axes. */
    CENTER(3);

    private final byte protocolId;

    Billboard(int id) {
        this.protocolId = (byte) id;
    }

    public byte protocolId() {
        return protocolId;
    }

    public static Billboard fromName(String name, Billboard fallback) {
        if (name == null) return fallback;
        return switch (name.toUpperCase()) {
            case "FIXED"      -> FIXED;
            case "VERTICAL"   -> VERTICAL;
            case "HORIZONTAL" -> HORIZONTAL;
            case "CENTER"     -> CENTER;
            default           -> fallback;
        };
    }
}
