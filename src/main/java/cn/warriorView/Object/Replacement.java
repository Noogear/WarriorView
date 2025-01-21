package cn.warriorView.Object;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Replacement {
    private final char[] numbers;
    private final Map<String, String> chars;
    private final boolean enabled;

    public Replacement(char[] numbers, List<String> list) {
        this.numbers = numbers;
        Map<String, String> replaceChar = new HashMap<>();
        for (String element : list) {
            String[] parts = element.split("#");
            if (parts.length == 2) {
                replaceChar.put(parts[0], parts[1]);
            }
        }
        this.chars = replaceChar;
        this.enabled = numbers.length != 0 || !chars.isEmpty();
    }

    public String replaceNumber(String text) {
        char[] chars = text.toCharArray();
        int length = chars.length;
        char[] result = new char[length];
        for (int i = 0; i < length; i++) {
            if(Character.isDigit(chars[i])){
                result[i] = numbers[chars[i] - '0'];
                continue;
            }
            result[i] = chars[i];

        }
        return new String(result);
    }

    public String replaceChar(String text) {
        for (String k : chars.keySet()) {
            text = text.replace(k, chars.get(k));
        }
        return text;
    }

    public String replaceAll(String text) {
        if (enabled) {
            String replace = replaceNumber(text);
            replace = replaceChar(replace);
            return replace;
        }
        return text;
    }

}
