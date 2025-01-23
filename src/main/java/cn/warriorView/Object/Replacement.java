package cn.warriorView.Object;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Replacement {
    private final String[] numbers;
    private final Map<String, String> chars;
    private final boolean isNumber;
    private final boolean isChar;

    protected Replacement(String[] numbers, Map<String, String> chars, boolean isNumber, boolean isChar) {
        this.numbers = numbers;
        this.chars = chars;
        this.isNumber = isNumber;
        this.isChar = isChar;
    }

    public static Replacement create(String[] numbers, List<String> list) {
        boolean hasNumber = false;
        boolean hasChar = true;
        if (numbers.length == 10) {
            hasNumber = true;
        }
        Map<String, String> replaceChar = new HashMap<>();
        for (String element : list) {
            String[] parts = element.split("#");
            if (parts.length == 2) {
                replaceChar.put(parts[0], parts[1]);
            }
        }
        if (replaceChar.isEmpty()) {
            hasChar = false;
        }
        return new Replacement(numbers, replaceChar, hasNumber, hasChar);
    }

    private String replaceNumber(String text) {
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

    private String replaceChar(String text) {
        for (String k : chars.keySet()) {
            text = text.replace(k, chars.get(k));
        }
        return text;
    }

    public String replaceAll(String text) {
        if (isNumber) {
            text = replaceNumber(text);
        }
        if (isChar) {
            text = replaceChar(text);
        }
        return text;
    }
}
