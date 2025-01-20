package cn.warriorView.Util;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class MathUtil {

    public static float round(Float num, int decimalPlaces) {
        return new BigDecimal(num).setScale(decimalPlaces, RoundingMode.HALF_UP).floatValue();
    }

}
