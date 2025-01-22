package cn.warriorView.Util;

import java.math.BigDecimal;
import java.math.RoundingMode;

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
}
