package cn.warriorview.formatter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 全面测试 CharReplacer 密封接口的三种实现：None、Latin1Only、WithExt。
 * 覆盖零分配特性、边界条件和混合码点替换。
 */
@DisplayName("CharReplacer 字符替换引擎测试")
class TestCharReplacer {

    // ── None 实现 ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("None（恒等替换器）")
    class NoneTests {

        @Test
        @DisplayName("NONE 单例返回同一引用")
        void noneSingleton() {
            assertSame(CharReplacer.NONE, CharReplacer.NONE);
        }

        @Test
        @DisplayName("apply 原样返回输入字符串引用")
        void applyReturnsSameReference() {
            String input = "Hello, World! 12345";
            assertSame(input, CharReplacer.NONE.apply(input));
        }

        @Test
        @DisplayName("空字符串返回空字符串")
        void emptyString() {
            assertSame("", CharReplacer.NONE.apply(""));
        }

        @Test
        @DisplayName("None 是 CharReplacer.None 类型")
        void typeCheck() {
            assertInstanceOf(CharReplacer.None.class, CharReplacer.NONE);
        }
    }

    // ── 工厂方法 of() ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("of() 工厂方法")
    class FactoryTests {

        @Test
        @DisplayName("null 输入 → NONE")
        void nullInputProducesNone() {
            CharReplacer r = CharReplacer.of(null, null);
            assertInstanceOf(CharReplacer.None.class, r);
        }

        @Test
        @DisplayName("空 ext map → None 或 Latin1Only")
        void emptyExtMap() {
            CharReplacer r = CharReplacer.of(null, Map.of());
            assertInstanceOf(CharReplacer.None.class, r);
        }

        @Test
        @DisplayName("仅 Latin1 表 → Latin1Only")
        void latin1Only() {
            String[] table = new String[256];
            table['0'] = "𝟘";
            CharReplacer r = CharReplacer.of(table, null);
            assertInstanceOf(CharReplacer.Latin1Only.class, r);
        }

        @Test
        @DisplayName("Latin1 + ext → WithExt")
        void withExt() {
            String[] table = new String[256];
            table['A'] = "𝔸";
            Map<Integer, String> ext = Map.of(0x4E2D, "中替");
            CharReplacer r = CharReplacer.of(table, ext);
            assertInstanceOf(CharReplacer.WithExt.class, r);
        }

        @Test
        @DisplayName("仅 ext → WithExt（自动补空 Latin1 表）")
        void onlyExt() {
            Map<Integer, String> ext = Map.of(0x4E2D, "中替");
            CharReplacer r = CharReplacer.of(null, ext);
            assertInstanceOf(CharReplacer.WithExt.class, r);
        }
    }

    // ── Latin1Only 实现 ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("Latin1Only")
    class Latin1OnlyTests {

        private CharReplacer createDigitReplacer() {
            String[] table = new String[256];
            table['0'] = "𝟘";
            table['1'] = "𝟙";
            table['2'] = "𝟚";
            table['3'] = "𝟛";
            table['4'] = "𝟜";
            table['5'] = "𝟝";
            table['6'] = "𝟞";
            table['7'] = "𝟟";
            table['8'] = "𝟠";
            table['9'] = "𝟡";
            table['.'] = "·";
            return CharReplacer.of(table, null);
        }

        @Test
        @DisplayName("数字被替换为 double-struck 字符")
        void digitReplacement() {
            CharReplacer r = createDigitReplacer();
            assertEquals("𝟙𝟚𝟛·𝟝", r.apply("123.5"));
        }

        @Test
        @DisplayName("无替换字符时返回原字符串引用（零分配）")
        void noReplacementReturnsOriginal() {
            CharReplacer r = createDigitReplacer();
            String input = "Hello World";
            assertSame(input, r.apply(input));
        }

        @Test
        @DisplayName("混合替换与非替换字符")
        void mixedContent() {
            CharReplacer r = createDigitReplacer();
            assertEquals("HP: 𝟙𝟘𝟘·𝟘", r.apply("HP: 100.0"));
        }

        @Test
        @DisplayName("空字符串不报错")
        void emptyString() {
            CharReplacer r = createDigitReplacer();
            assertEquals("", r.apply(""));
        }

        @Test
        @DisplayName("全替换字符串")
        void allReplacement() {
            CharReplacer r = createDigitReplacer();
            assertEquals("𝟘𝟙𝟚𝟛𝟜𝟝𝟞𝟟𝟠𝟡", r.apply("0123456789"));
        }

        @Test
        @DisplayName("单字符替换")
        void singleChar() {
            CharReplacer r = createDigitReplacer();
            assertEquals("𝟝", r.apply("5"));
        }
    }

    // ── WithExt 实现 ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("WithExt")
    class WithExtTests {

        @Test
        @DisplayName("高码点字符替换")
        void highCodePointReplacement() {
            Map<Integer, String> ext = new HashMap<>();
            ext.put((int) '中', "[ZH]");  // 码点 0x4E2D
            CharReplacer r = CharReplacer.of(null, ext);
            assertEquals("[ZH]文", r.apply("中文"));
        }

        @Test
        @DisplayName("Latin1 和高码点混合替换")
        void mixedReplacement() {
            String[] table = new String[256];
            table['A'] = "[a]";
            Map<Integer, String> ext = new HashMap<>();
            ext.put((int) '你', "[ni]");
            CharReplacer r = CharReplacer.of(table, ext);
            assertEquals("[a]B[ni]好", r.apply("AB你好"));
        }

        @Test
        @DisplayName("无匹配时返回原字符串引用")
        void noMatchReturnsOriginal() {
            String[] table = new String[256];
            table['Z'] = "z_rep";
            Map<Integer, String> ext = new HashMap<>();
            ext.put(0xFFFF, "rep");
            CharReplacer r = CharReplacer.of(table, ext);
            String input = "Hello World";
            assertSame(input, r.apply(input));
        }
    }
}
