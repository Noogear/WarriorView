package cn.warriorView.Util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class MathUtil {

    public static float round(Float num, int decimalPlaces) {
        return new BigDecimal(num).setScale(decimalPlaces, RoundingMode.HALF_UP).floatValue();
    }

    public static float round(double num, int decimalPlaces) {
        return new BigDecimal(num).setScale(decimalPlaces, RoundingMode.HALF_UP).floatValue();
    }

    public static byte convertIntToByte(int num) {
        if (num < Byte.MIN_VALUE) {
            return Byte.MIN_VALUE;
        } else if (num > Byte.MAX_VALUE) {
            return Byte.MAX_VALUE;
        } else {
            return (byte) num;
        }
    }

    public static byte opacityFromPercent(double percent) {
        double clamped = Math.max(0.0, Math.min(100.0, percent));
        return (byte) Math.round(clamped * 255 / 100.0);
    }

    public static <K> int parallelCount(Map<K, ?> map1, Map<K, ?> map2) {
        return Stream.concat(map1.keySet().stream(), map2.keySet().stream())
                .collect(Collectors.toSet())
                .size();
    }
}
