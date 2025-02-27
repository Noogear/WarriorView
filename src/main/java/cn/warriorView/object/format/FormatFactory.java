package cn.warriorView.object.format;

import cn.warriorView.object.format.number.NumberQuantize;
import cn.warriorView.object.format.text.TextReplace;
import cn.warriorView.util.TextUtils;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;

public class FormatFactory {

    private static final long[] POW10_CACHE = new long[18];

    static {
        long value = 1;
        for (int i = 0; i < POW10_CACHE.length; i++) {
            POW10_CACHE[i] = value;
            value *= 10;
        }
    }

    public static TextReplace createTextFormat(@NotNull Map<String, String> rules) {
        String[] numMap = new String[10];
        Arrays.setAll(numMap, i -> String.valueOf((char) ('0' + i)));

        boolean hasNumberReplace = false;
        String[] ascii = new String[128];
        boolean hasAsciiReplace = false;
        Map<Character, String> extended = new HashMap<>();
        boolean hasExtendedReplace = false;

        for (Map.Entry<String, String> e : rules.entrySet()) {
            String key = e.getKey();
            String val = e.getValue() != null ? e.getValue() : "";
            char k = key.charAt(0);

            if (k >= '0' && k <= '9') {
                int index = k - '0';
                numMap[index] = val;
                if (!String.valueOf(k).equals(val)) {
                    hasNumberReplace = true;
                }
            } else {
                if (k < 128) {
                    ascii[k] = val;
                    hasAsciiReplace = true;
                } else {
                    extended.put(k, val);
                    hasExtendedReplace = true;
                }
            }
        }
        return new TextReplace(numMap, ascii, extended,
                hasNumberReplace, hasAsciiReplace, hasExtendedReplace);
    }

    public static NumberQuantize createNumberFormat(Map<Integer, String> unitMap) {
        if (unitMap.isEmpty()) {
            throw new IllegalArgumentException("Unit map cannot be empty");
        }

        var sortedEntries = unitMap.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .toList();

        double[] thresholds = new double[sortedEntries.size()];
        String[] units = new String[sortedEntries.size()];

        for (int i = 0; i < sortedEntries.size(); i++) {
            int exp = sortedEntries.get(i).getKey();
            thresholds[i] = (exp >= 0 && exp < POW10_CACHE.length) ?
                    POW10_CACHE[exp] : Math.pow(10, exp);
            units[i] = TextUtils.unescapeUnicode(sortedEntries.get(i).getValue());
        }

        return new NumberQuantize(thresholds, units);
    }

    public static TextFormat create(String text, INumber number, IText textFormat) {
        Matcher matcher = TextUtils.formatMatch(text);
        if (!matcher.find()) {
            throw new IllegalArgumentException("Invalid format string: " + text);
        }

        String prefix = matcher.group(1) != null ? matcher.group(1) : "";
        String decimalPart = matcher.group(2);
        String suffix = matcher.group(3) != null ? matcher.group(3) : "";

        int decimalPlaces = 0;
        if (decimalPart != null && !decimalPart.isEmpty()) {
            try {
                decimalPlaces = Integer.parseInt(decimalPart);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid decimal format: " + decimalPart);
            }
        }
        return new TextFormat(prefix, suffix, decimalPlaces, number, textFormat);
    }
}
