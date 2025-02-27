package cn.warriorView.object.format.text;

import cn.warriorView.object.format.IText;
import cn.warriorView.util.TextUtils;
import cn.warriorView.util.XLogger;
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

    public TextReplace(String[] numMap, String[] asciiMap,
                       Map<Character, String> extendedMap,
                       boolean hasNum, boolean hasAscii, boolean hasExt) {
        System.arraycopy(numMap, 0, this.numberMap, 0, 10);
        System.arraycopy(asciiMap, 0, this.asciiMap, 0, 128);
        this.extendedMap = Map.copyOf(extendedMap);
        this.hasNumberReplace = hasNum;
        this.hasAsciiReplace = hasAscii;
        this.hasExtendedReplace = hasExt;
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