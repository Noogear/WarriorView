package cn.warriorView.view.meta;

import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.manager.server.VersionComparison;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataType;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.util.Vector3f;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;

import static cn.warriorView.util.PacketUtil.isVersion;

public class TextDisplayMeta {

    public static final boolean ABOVE_1_20_2 = isVersion(ServerVersion.V_1_20_2, VersionComparison.NEWER_THAN_OR_EQUALS);
    public static final byte OFFSET = 0;
    public static final byte MAX_OFFSET = OFFSET + 8;
    public static final byte DISPLAY_OFFSET = MAX_OFFSET;
    public static final byte DISPLAY_MAX_OFFSET;
    public static final byte TEXT_OFFSET = DISPLAY_MAX_OFFSET;
    public static final byte TEXT_MAX_OFFSET = offset(TEXT_OFFSET, 5);
    private static final byte SHADOW = 1;
    private static final byte SEE_THROUGH = 2;
    private static final byte USE_DEFAULT_BACKGROUND = 4;
    private static final byte ALIGN_LEFT = 8;
    private static final byte ALIGN_RIGHT = 16;

    static {
        if (ABOVE_1_20_2) {
            DISPLAY_MAX_OFFSET = DISPLAY_OFFSET + 15;
        } else {
            DISPLAY_MAX_OFFSET = DISPLAY_OFFSET + 14;
        }
    }

    protected static byte offset(byte value, int amount) {
        return (byte) (value + amount);
    }

    protected static <T> EntityData createEntityData(byte index, @NotNull EntityDataType<T> dataType, T value) {
        return new EntityData(index, dataType, value);
    }

    public static EntityData billboardConstraints(BillboardConstraints value) {
        byte offset = offset(DISPLAY_OFFSET, 7);
        if (!ABOVE_1_20_2) {
            offset = offset(DISPLAY_OFFSET, 6);
        }
        return createEntityData(offset, EntityDataTypes.BYTE, (byte) value.ordinal());
    }

    public static EntityData brightnessOverride(int value) {
        byte offset = offset(DISPLAY_OFFSET, 8);
        if (!ABOVE_1_20_2) {
            offset = offset(DISPLAY_OFFSET, 7);
        }
        return createEntityData(offset, EntityDataTypes.INT, value);
    }

    public static EntityData viewRange(float value) {
        byte offset = offset(DISPLAY_OFFSET, 9);
        if (!ABOVE_1_20_2) {
            offset = offset(DISPLAY_OFFSET, 8);
        }
        return createEntityData(offset, EntityDataTypes.FLOAT, value);
    }

    public static EntityData transformationInterpolationDuration(int value) {
        return createEntityData(offset(DISPLAY_OFFSET, 1), EntityDataTypes.INT, value);
    }

    public static EntityData positionRotationInterpolationDuration(int value) {
        return createEntityData(offset(DISPLAY_OFFSET, 2), EntityDataTypes.INT, value);
    }

    public static EntityData scale(Vector3f value) {
        byte offset = offset(DISPLAY_OFFSET, 4);
        if (!ABOVE_1_20_2) {
            offset = offset(DISPLAY_OFFSET, 3);
        }
        return createEntityData(offset, EntityDataTypes.VECTOR3F, value);
    }

    public static EntityData text(Component component) {
        return createEntityData(TEXT_OFFSET, EntityDataTypes.ADV_COMPONENT, component);
    }

    public static EntityData backgroundColor(int value) {
        return createEntityData(offset(TEXT_OFFSET, 2), EntityDataTypes.INT, value);
    }

    public static EntityData textOpacity(byte value) {
        return createEntityData(offset(TEXT_OFFSET, 3), EntityDataTypes.BYTE, value);
    }

    public static EntityData maskBitGroup(boolean isShadow, boolean isSeeThrough,
                                          boolean isUseDefaultBackground,
                                          boolean isAlignLeft, boolean isAlignRight) {
        byte index = offset(TEXT_OFFSET, 4);
        byte mask = 0;
        if (isShadow) mask |= SHADOW;
        if (isSeeThrough) mask |= SEE_THROUGH;
        if (isUseDefaultBackground) mask |= USE_DEFAULT_BACKGROUND;
        if (isAlignLeft) mask |= ALIGN_LEFT;
        if (isAlignRight) mask |= ALIGN_RIGHT;
        return createEntityData(index, EntityDataTypes.BYTE, mask);
    }

    public enum BillboardConstraints {
        FIXED,
        VERTICAL,
        HORIZONTAL,
        CENTER
    }


}
