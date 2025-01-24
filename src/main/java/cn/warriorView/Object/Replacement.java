package cn.warriorView.Object;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Replacement {
    private final String[] numbers;
    private final Map<String, String> chars;
    private final boolean hasNumber;
    private final boolean hasChar;

    protected Replacement(String[] numbers, Map<String, String> chars, boolean hasNumber, boolean hasChar) {
        this.numbers = numbers;
        this.chars = chars;
        this.hasNumber = hasNumber;
        this.hasChar = hasChar;
    }

    public static Replacement create(String[] numbers, List<String> list) {
        boolean hasNumber = true;
        boolean hasChar = true;
        if (numbers.length < 10) {
            hasNumber = false;
        }
        Map<String, String> chars = new HashMap<>();
        for (String element : list) {
            String[] parts = element.split("#");
            if (parts.length == 2) {
                chars.put(parts[0], parts[1]);
            }
        }
        if (chars.isEmpty()) {
            hasChar = false;
        }
        return new Replacement(numbers, chars, hasNumber, hasChar);
    }

    private String replaceNumber(String text) {
        StringBuilder result = new StringBuilder(text.length());
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
        if (hasNumber) {
            text = replaceNumber(text);
        }
        if (hasChar) {
            text = replaceChar(text);
        }
        return text;
    }
}
