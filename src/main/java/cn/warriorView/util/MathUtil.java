package cn.warriorView.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

public class MathUtil {

    public static float round(double num) {
        return round(num, 2);
    }

    public static float round(double num, int decimalPlaces) {
        validateArguments(decimalPlaces);
        return new BigDecimal(num)
                .setScale(decimalPlaces, RoundingMode.HALF_UP)
                .floatValue();
    }

    private static void validateArguments(int decimalPlaces) {
        if (decimalPlaces < 0) {
            throw new IllegalArgumentException("Decimal places must be >= 0");
        }
    }

    public static byte convertIntToByte(int num) {
        return (byte) Math.max(Byte.MIN_VALUE, Math.min(Byte.MAX_VALUE, num));
    }

    public static byte opacityFromPercent(double percent) {
        double clamped = Math.max(0.0, Math.min(100.0, percent));
        return (byte) Math.round(clamped * 2.55); // 255/100=2.55
    }

    public static float[] coverListToArray(List<Float> list, int length, float defaultValue) {
        float[] array = new float[length];
        int listSize = (list != null) ? list.size() : 0;
        for (int i = 0; i < length; i++) {
            array[i] = (i < listSize && list.get(i) != null) ? list.get(i) : defaultValue;
        }
        return array;
    }

}