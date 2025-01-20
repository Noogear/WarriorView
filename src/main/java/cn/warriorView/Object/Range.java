package cn.warriorView.Object;

import cn.warriorView.Util.MathUtil;

import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;

public class Range {

    private final double start;
    private final double end;
    private final double[] fixed;

    public Range(String value) {
        double start = 0;
        double end = 0;
        double[] fixed = new double[0];
        if (value.contains("-")) {
            String[] range = value.replaceAll(" ", "").split("-");
            if (range.length >= 2) {
                start = MathUtil.round(Double.parseDouble(range[0]), 2);
                end = MathUtil.round(Double.parseDouble(range[range.length - 1]), 2);
            }
        } else {
            fixed = Arrays.stream(value.replace(" ", "").split(","))
                    .mapToDouble(Double::parseDouble)
                    .map(d -> MathUtil.round(d, 2))
                    .toArray();
        }
        this.start = start;
        this.end = end;
        this.fixed = fixed;
    }

    public double getRandom() {
        if (fixed.length == 0) {
            return ThreadLocalRandom.current().nextDouble(start, end);
        }
        return fixed[ThreadLocalRandom.current().nextInt(fixed.length)];
    }
}
