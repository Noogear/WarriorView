package cn.warriorView.Util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TextUtils {
    private static final Pattern UNICODE_PATTERN = Pattern.compile("\\\\u([0-9a-fA-F]{4})");
    private static final Pattern FORMAT_REGEX = Pattern.compile("^(.*?)%(\\d+)f(.*)$");
    private static final ThreadLocal<StringBuilder> TL_BUILDER =
            ThreadLocal.withInitial(() -> new StringBuilder(16));

    public static String unescapeUnicode(String input) {
        if (input == null || !input.contains("\\u")) {
            return input;
        }
        return UNICODE_PATTERN.matcher(input).replaceAll(mr -> {
            try {
                return convertHexToChar(mr.group(1));
            } catch (NumberFormatException e) {
                return mr.group();
            }
        });
    }

    private static String convertHexToChar(String hex) {
        StringBuilder sb = TL_BUILDER.get();
        sb.setLength(0);
        return sb.append((char) Integer.parseInt(hex, 16)).toString();
    }

    public static Matcher formatMatch(String format) {
        return FORMAT_REGEX.matcher(format);
    }
}