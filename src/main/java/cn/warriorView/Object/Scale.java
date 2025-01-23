package cn.warriorView.Object;

import cn.warriorView.Util.MathUtil;
import com.github.retrooper.packetevents.util.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class Scale {

    private final float start;
    private final float end;
    private final Vector3f[] fixed;
    private final boolean isFixed;

    public Scale(float start, float end, Vector3f[] fixed, boolean isFixed) {
        this.start = start;
        this.end = end;
        this.fixed = fixed;
        this.isFixed = isFixed;
    }

    public static Scale create(String value) {
        float start = 0;
        float end = 0;
        Vector3f[] fixed = new Vector3f[0];
        boolean isFixed;
        if (value.contains("-")) {
            String[] range = value.replaceAll(" ", "").split("-");
            if (range.length >= 2) {
                start = MathUtil.round(Float.parseFloat(range[0]), 2);
                end = MathUtil.round(Float.parseFloat(range[range.length - 1]), 2);
            }
            isFixed = false;
        } else {
            List<Float> floatList = new ArrayList<>();
            String[] strArray = value.replace(" ", "").split(",");
            for (String str : strArray) {
                floatList.add(MathUtil.round(Float.parseFloat(str), 2));
            }
            fixed = new Vector3f[floatList.size()];
            for (int i = 0; i < floatList.size(); i++) {
                fixed[i] = new Vector3f(floatList.get(i), floatList.get(i), floatList.get(i));
            }
            isFixed = true;
        }
        return new Scale(start, end, fixed, isFixed);
    }

    public Vector3f getRandom() {
        if (isFixed) {
            return fixed[ThreadLocalRandom.current().nextInt(fixed.length)];
        }
        float scale = ThreadLocalRandom.current().nextFloat(start, end);
        return new Vector3f(scale, scale, scale);
    }
}
