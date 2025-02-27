package cn.warriorView.object.scale.type;

import cn.warriorView.object.scale.IScale;
import com.github.retrooper.packetevents.util.Vector3f;

import java.util.concurrent.ThreadLocalRandom;

public class ScaleRange implements IScale {

    private final float start;
    private final float end;
    private final Vector3f[] fixed;
    private final int length;
    private final boolean isFixed;

    public ScaleRange(float start, float end, Vector3f[] fixed, boolean isFixed) {
        this.start = start;
        this.end = end;
        this.fixed = fixed;
        this.length = fixed.length;
        this.isFixed = isFixed;
    }

    @Override
    public Vector3f get() {
        if (isFixed) {
            return fixed[ThreadLocalRandom.current().nextInt(length)];
        }
        float scale = ThreadLocalRandom.current().nextFloat(start, end);
        return new Vector3f(scale, scale, scale);
    }
}
