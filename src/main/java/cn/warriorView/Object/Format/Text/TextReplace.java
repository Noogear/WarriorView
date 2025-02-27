package cn.warriorView.object.format.text;

import cn.warriorView.object.format.IText;
import cn.warriorView.util.TextUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class TextReplace implements IText {
    private final String[] numberMap = new String[10];
    private final String[] asciiMap = new String[128];
    private final Map<Character, String> extendedMap;
    private final boolean hasNumberReplace;
    private final boolean hasAsciiReplace;
    private final boolean hasExtendedReplace;

    protected TextReplace(String[] numMap, String[] asciiMap,
                          Map<Character, String> extendedMap,
                          boolean hasNum, boolean hasAscii, boolean hasExt) {
        System.arraycopy(numMap, 0, this.numberMap, 0, 10);
        System.arraycopy(asciiMap, 0, this.asciiMap, 0, 128);
        this.extendedMap = Map.copyOf(extendedMap);
        this.hasNumberReplace = hasNum;
        this.hasAsciiReplace = hasAscii;
        this.hasExtendedReplace = hasExt;
    }

    public static TextReplace create(Map<String, String> rules) {
        String[] numMap = new String[10];
        Arrays.setAll(numMap, i -> String.valueOf((char) ('0' + i)));

        boolean hasNumberReplace = false;
        String[] ascii = new String[128];
        boolean hasAsciiReplace = false;
        Map<Character, String> extended = new HashMap<>();
        boolean hasExtendedReplace = false;

        for (Map.Entry<String, String> e : rules.entrySet()) {
            String key = e.getKey();
            String val = e.getValue() != null ? TextUtils.unescapeUnicode(e.getValue()) : "";

            if (key.length() != 1) {
                throw new IllegalArgumentException("Rule key must be single character: " + key);
            }
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

    @Override
    public Component format(String text) {
        StringBuilder buffer = new StringBuilder(text.length() * 2);
        boolean inTag = false;
        for (char c : text.toCharArray()) {
            if (c == '<') {
                inTag = true;
                buffer.append(c);
            } else if (c == '>') {
                inTag = false;
                buffer.append(c);
            } else if (inTag) {
                buffer.append(c);
            } else {
                if (processNumber(c, buffer)) continue;
                if (processAscii(c, buffer)) continue;
                if (processExtended(c, buffer)) continue;
                buffer.append(c);
            }
        }
        return MiniMessage.miniMessage().deserialize(buffer.toString());
    }

    private boolean processNumber(char c, StringBuilder buffer) {
        if (hasNumberReplace && c >= '0' && c <= '9') {
            buffer.append(numberMap[c - '0']);
            return true;
        }
        return false;
    }

    private boolean processAscii(char c, StringBuilder buffer) {
        if (hasAsciiReplace && c < 128) {
            String rep = asciiMap[c];
            if (rep != null) {
                buffer.append(rep);
                return true;
            }
        }
        return false;
    }

    private boolean processExtended(char c, StringBuilder buffer) {
        if (hasExtendedReplace) {
            String rep = extendedMap.get(c);
            if (rep != null) {
                buffer.append(rep);
                return true;
            }
        }
        return false;
    }
}