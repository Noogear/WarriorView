package cn.warriorView.object.scale.type;

import cn.warriorView.object.scale.IScale;
import com.github.retrooper.packetevents.util.Vector3f;

public class ScaleCommon implements IScale {
    private final Vector3f scale;

    public ScaleCommon(Vector3f scale) {
        this.scale = scale;
    }

    @Override
    public Vector3f get() {
        return scale;
    }
}
