package cn.warriorView.Object.Format;

import java.util.HashMap;
import java.util.Map;

public class StringReplace {
    private final char[] numberMap = new char[10];     // 数字->字符
    private final String[] asciiMap = new String[128]; // ASCII替换表
    private final Map<Character, String> extendedMap;  // 扩展字符映射
    private final byte stateFlags;                     // 状态标志位

    protected StringReplace(char[] numMap, String[] asciiMap,
                              Map<Character, String> extendedMap, byte state) {
        System.arraycopy(numMap,  0, this.numberMap,  0, 10);
        System.arraycopy(asciiMap,  0, this.asciiMap,  0, 128);
        this.extendedMap  = Map.copyOf(extendedMap);
        this.stateFlags  = state;
    }

    public static StringReplace create(Map<String, String> rules){

        char[] numMap = new char[10];
        boolean hasNumberReplace = false;
        for (int i = 0; i < 10; i++) {
            String key = String.valueOf(i);
            String rep = rules.getOrDefault(key,  "");
            numMap[i] = rep.isEmpty()  ? (char)('0' + i) : rep.charAt(0);
            if (numMap[i] != ('0' + i)) hasNumberReplace = true;
        }

        // 构建字符映射
        String[] ascii = new String[128];
        Map<Character, String> extended = new HashMap<>();
        boolean hasAsciiReplace = false, hasExtendedReplace = false;

        for (Map.Entry<String, String> e : rules.entrySet())  {
            String key = e.getKey();
            if (key.length()  != 1) continue;  // 忽略非单字符键

            char k = key.charAt(0);
            String val = e.getValue();

            // 跳过已处理的数字键
            if (k >= '0' && k <= '9') continue;

            if (k < 128) {
                ascii[k] = val;
                hasAsciiReplace = hasAsciiReplace || !val.isEmpty();
            } else {
                extended.put(k,  val);
                hasExtendedReplace = hasExtendedReplace || !val.isEmpty();
            }
        }

        // 计算状态标志（优化位运算逻辑）
        byte state = (byte)(
                (hasNumberReplace ? 0b0001 : 0) |
                        (hasAsciiReplace  ? 0b0010 : 0) |
                        (hasExtendedReplace ? 0b0100 : 0)
        );
        return new StringReplace(numMap, ascii, extended, state);


    }
    public String replace(String text) {
        if (text.isEmpty()  || stateFlags == 0) return text;

        char[] src = text.toCharArray();
        char[] buffer = new char[32];
        int bufferIndex = 0;

        for (char c : src) {
            // 第一优先级：数字替换
            if ((stateFlags & 0b0001) != 0 && c >= '0' && c <= '9') {
                buffer[bufferIndex++] = numberMap[c - '0'];
                continue;
            }

            // 第二优先级：ASCII替换
            if (c < 128 && (stateFlags & 0b0010) != 0) {
                String rep = asciiMap[c];
                if (rep != null) {
                    rep.getChars(0,  rep.length(),  buffer, bufferIndex);
                    bufferIndex += rep.length();
                    continue;
                }
            }

            // 第三优先级：扩展字符替换
            if ((stateFlags & 0b0100) != 0) {
                String rep = extendedMap.get(c);
                if (rep != null) {
                    rep.getChars(0,  rep.length(),  buffer, bufferIndex);
                    bufferIndex += rep.length();
                    continue;
                }
            }

            // 无替换情况
            buffer[bufferIndex++] = c;
        }
        return new String(buffer, 0, bufferIndex);
    }

}
