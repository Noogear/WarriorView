package cn.warriorView.object.format;

import cn.warriorView.util.TextUtils;
import net.kyori.adventure.text.Component;

import java.util.regex.Matcher;

public class TextFormat {

    private final String prefix;
    private final String suffix;
    private final int precision;
    private final INumber numberFormat;
    private final IText textFormat;

    protected TextFormat(String prefix, String suffix, int precision, INumber numberFormat, IText textFormat) {
        this.prefix = prefix;
        this.suffix = suffix;
        this.precision = precision;
        this.numberFormat = numberFormat;
        this.textFormat = textFormat;
    }

    public Component get(double value) {
        return textFormat.format(numberFormat.format(prefix, suffix, precision, value));
    }

}
