package cn.warriorView.object.scale;

import cn.warriorView.object.scale.type.ScaleCommon;
import cn.warriorView.object.scale.type.ScaleRange;
import cn.warriorView.util.MathUtil;
import com.github.retrooper.packetevents.util.Vector3f;

import java.util.ArrayList;
import java.util.List;

public class ScaleFactory {

    public static IScale create(String value) {
        if (value.contains(",") || value.contains("-")) {
            float start = 0;
            float end = 0;
            Vector3f[] fixed = new Vector3f[0];
            boolean isFixed;
            if (value.contains("-")) {
                String[] range = value.replaceAll(" ", "").split("-");
                if (range.length >= 2) {
                    start = MathUtil.round(Float.parseFloat(range[0]));
                    end = MathUtil.round(Float.parseFloat(range[range.length - 1]));
                }
                isFixed = false;
            } else {
                List<Float> floatList = new ArrayList<>();
                String[] strArray = value.replace(" ", "").split(",");
                for (String str : strArray) {
                    floatList.add(MathUtil.round(Float.parseFloat(str)));
                }
                fixed = new Vector3f[floatList.size()];
                for (int i = 0; i < floatList.size(); i++) {
                    fixed[i] = new Vector3f(floatList.get(i), floatList.get(i), floatList.get(i));
                }
                isFixed = true;
            }
            return new ScaleRange(start, end, fixed, isFixed);
        }
        float scale = MathUtil.round(Double.parseDouble(value));
        return new ScaleCommon(new Vector3f(scale, scale, scale));
    }

}
