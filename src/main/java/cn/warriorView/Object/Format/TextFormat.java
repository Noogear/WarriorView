package cn.warriorView.Object.Format;

import cn.warriorView.Object.Format.Number.INumber;
import cn.warriorView.Object.Format.Text.IText;
import cn.warriorView.Util.TextUtils;
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

    public static TextFormat create(String text, INumber numberFormat, IText textFormat) {
        Matcher matcher = TextUtils.formatMatch(text);
        return new TextFormat(matcher.group(1), matcher.group(3), Integer.parseInt(matcher.group(2)), numberFormat, textFormat);
    }

    public Component get(double value) {
        return textFormat.format(numberFormat.format(prefix, suffix, precision, value));
    }

}
