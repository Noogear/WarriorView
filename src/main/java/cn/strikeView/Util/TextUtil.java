package cn.strikeView.Util;

public class TextUtil {

    public static String replaceNumber(String text, char[] charGroup) {
        char[] chars = text.toCharArray();
        int length = chars.length;
        char[] result = new char[length];
        for (int i = 0; i < length; i++) {
            result[i] = charGroup[chars[i] - '0'];
        }
        return new String(result);
    }


}
