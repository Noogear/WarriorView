package cn.warriorView.Object;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Replacement {
    private final String[] numbers;
    private final Map<String, String> chars;
    private final boolean enabled;

    public Replacement(String[] numbers, List<String> list) {
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
        StringBuilder result = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (Character.isDigit(c)) {
                result.append(numbers[c - '0']);
                continue;
            }
            result.append(c);
        }
        return result.toString();
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
