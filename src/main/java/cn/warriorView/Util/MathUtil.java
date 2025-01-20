package cn.warriorView.Util;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class MathUtil {

    public static double round(double num, int decimalPlaces) {
        return new BigDecimal(num).setScale(decimalPlaces, RoundingMode.HALF_UP).doubleValue();
    }

}
