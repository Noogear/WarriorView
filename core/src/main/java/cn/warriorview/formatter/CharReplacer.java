package cn.warriorview.formatter;

import java.util.Map;

/**
 * 高性能单字符替换引擎（内部实现，不对外暴露）。
 *
 * <p>密封接口在<b>加载期</b>选定最优实现，运行期无 null 判断：
 * <ul>
 *   <li>{@link None}        — 恒等，零开销，直接返回原字符串引用</li>
 *   <li>{@link Latin1Only}  — 仅 Latin-1（码点 0–255）字符，{@code String[256]} O(1) 寻址</li>
 *   <li>{@link WithExt}     — Latin-1 + 高码点（>255）HashMap 回退</li>
 * </ul>
 *
 * <p>所有实现共同特性：若输入串中无需替换的字符，直接返回原字符串引用（零分配）。
 */
sealed interface CharReplacer permits
        CharReplacer.None, CharReplacer.Latin1Only, CharReplacer.WithExt {

    /** 恒等单例，不做任何替换。 */
    CharReplacer NONE = new None();

    /**
     * 对字符串 {@code s} 应用字符替换规则。
     * <p>若整个字符串内没有任何字符需要替换，直接返回 {@code s} 本身（零分配）。
     */
    String apply(String s);

    // ── 实现 ──────────────────────────────────────────────────────────────────

    record None() implements CharReplacer {
        @Override public String apply(String s) { return s; }
    }

    record Latin1Only(String[] table) implements CharReplacer {
        @Override
        public String apply(String s) { return scan(s, table, null); }
    }

    record WithExt(String[] latin, Map<Integer, String> ext) implements CharReplacer {
        @Override
        public String apply(String s) { return scan(s, latin, ext); }
    }

    // ── 工厂 ──────────────────────────────────────────────────────────────────

    static CharReplacer of(String[] latinTable, Map<Integer, String> extTable) {
        final boolean hasLatin = latinTable != null;
        final boolean hasExt   = extTable != null && !extTable.isEmpty();
        if (!hasLatin && !hasExt) return NONE;
        if (!hasExt)              return new Latin1Only(latinTable);
        return new WithExt(hasLatin ? latinTable : new String[256], extTable);
    }

    // ── 共用扫描逻辑 ──────────────────────────────────────────────────────────

    private static String scan(String s, String[] latin, Map<Integer, String> ext) {
        final int len = s.length();
        StringBuilder sb = null;

        for (int i = 0; i < len; i++) {
            final char c = s.charAt(i);
            final String rep = c < 256
                    ? (latin != null ? latin[c] : null)
                    : (ext   != null ? ext.get((int) c) : null);

            if (rep != null) {
                if (sb == null) {
                    sb = new StringBuilder(len + 8);
                    if (i > 0) sb.append(s, 0, i);
                }
                sb.append(rep);
            } else if (sb != null) {
                sb.append(c);
            }
        }

        return sb != null ? sb.toString() : s;
    }
}
