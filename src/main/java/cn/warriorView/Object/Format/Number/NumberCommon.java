package cn.warriorView.Object.Format.Number;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class NumberCommon implements INumberFormat {

    @Override
    public String format(String prefix, String suffix, int precision, double value) {
        return prefix +
                BigDecimal.valueOf(value)
                        .setScale(precision, RoundingMode.DOWN)
                        .toPlainString() +
                suffix;
    }
}
