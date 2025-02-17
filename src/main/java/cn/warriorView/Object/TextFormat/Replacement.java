package cn.warriorView.Object.TextFormat;

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

    public static Replacement create(String[] numbers, Map<String, String> chars) {
        boolean hasNumber = true;
        boolean hasChar = true;
        if (numbers.length != 10) {
            hasNumber = false;
        }
        if (chars.isEmpty()) {
            hasChar = false;
        }
        return new Replacement(numbers, chars, hasNumber, hasChar);
    }

    private String replaceNumber(String text) {
        char[] chars = text.toCharArray();
        for (int i = 0; i < chars.length;  i++) {
            if (Character.isDigit(chars[i]))  {
                chars[i] = numbers[chars[i] - '0'].charAt(0);
            }
        }
        return new String(chars);
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
