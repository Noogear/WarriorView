package cn.warriorView.object.format.number;

import cn.warriorView.object.format.INumber;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class NumberCommon implements INumber {

    @Override
    public String format(String prefix, String suffix, int precision, double value) {
        return prefix +
                BigDecimal.valueOf(value)
                        .setScale(precision, RoundingMode.DOWN)
                        .toPlainString() +
                suffix;
    }
}
