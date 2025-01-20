package cn.warriorView.Object;

import cn.warriorView.Util.MathUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class Range {

    private final float start;
    private final float end;
    private final float[] fixed;

    public Range(String value) {
        float start = 0;
        float end = 0;
        float[] fixed = new float[0];
        if (value.contains("-")) {
            String[] range = value.replaceAll(" ", "").split("-");
            if (range.length >= 2) {
                start = MathUtil.round(Float.parseFloat(range[0]), 2);
                end = MathUtil.round(Float.parseFloat(range[range.length - 1]), 2);
            }
        } else {
            List<Float> floatList = new ArrayList<>();
            String[] strArray = value.replace(" ", "").split(",");
            for (String str : strArray) {
                floatList.add(MathUtil.round(Float.parseFloat(str), 2));
            }
            fixed = new float[floatList.size()];
            for (int i = 0; i < floatList.size(); i++) {
                fixed[i] = floatList.get(i);
            }
        }
        this.start = start;
        this.end = end;
        this.fixed = fixed;
    }

    public float getRandom() {
        if (fixed.length == 0) {
            return ThreadLocalRandom.current().nextFloat(start, end);
        }
        return fixed[ThreadLocalRandom.current().nextInt(fixed.length)];
    }
}
