package cn.warriorview.formatter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试 ValueFormatter 函数式接口及 NONE 默认实现。
 */
@DisplayName("ValueFormatter 数值格式化器测试")
class TestValueFormatter {

    // ── NONE 格式化器 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("NONE 精度 0 → 整数格式")
    void noneIntegerFormat() {
        assertEquals("123", ValueFormatter.NONE.format(123.456, 0));
        assertEquals("0", ValueFormatter.NONE.format(0, 0));
        assertEquals("-5", ValueFormatter.NONE.format(-5.9, 0));
    }

    @Test
    @DisplayName("NONE 精度 1 → 一位小数")
    void noneOneDecimal() {
        assertEquals("123.5", ValueFormatter.NONE.format(123.456, 1));
    }

    @Test
    @DisplayName("NONE 精度 2 → 两位小数")
    void noneTwoDecimals() {
        assertEquals("123.46", ValueFormatter.NONE.format(123.456, 2));
    }

    @Test
    @DisplayName("NONE 精度 3 → 三位小数")
    void noneThreeDecimals() {
        assertEquals("123.456", ValueFormatter.NONE.format(123.456, 3));
    }

    @Test
    @DisplayName("NONE 负精度按 0 处理")
    void noneNegativePrecision() {
        assertEquals("123", ValueFormatter.NONE.format(123.456, -5));
    }

    @Test
    @DisplayName("NONE 零值")
    void noneZeroValue() {
        assertEquals("0", ValueFormatter.NONE.format(0, 0));
        assertEquals("0.00", ValueFormatter.NONE.format(0, 2));
    }

    @Test
    @DisplayName("NONE 大数值")
    void noneLargeValue() {
        assertEquals("1000000", ValueFormatter.NONE.format(1_000_000, 0));
    }

    // ── 自定义 Lambda 实现 ──────────────────────────────────────────────────

    @Test
    @DisplayName("Lambda 实现：固定后缀")
    void lambdaWithSuffix() {
        ValueFormatter custom = (value, dp) -> String.format("%.0f HP", value);
        assertEquals("100 HP", custom.format(100, 0));
    }

    @Test
    @DisplayName("Lambda 实现：乘法预处理")
    void lambdaMultiplied() {
        ValueFormatter doubled = (value, dp) -> String.format("%.1f", value * 2);
        assertEquals("20.0", doubled.format(10.0, 1));
    }

    @Test
    @DisplayName("组合：CompactNumberFormatter + CharReplacer 通过 ValueFormatter")
    void composedFormatter() {
        // 模拟 NumberFormatRegistry.buildFormatter 的逻辑
        CompactNumberFormatter cnf = new CompactNumberFormatter.Builder()
                .add(1000, "K")
                .build();

        String[] latin = new String[256];
        latin['0'] = "𝟘";
        latin['1'] = "𝟙";
        latin['K'] = "千";
        CharReplacer cr = CharReplacer.of(latin, null);

        ValueFormatter composed = (v, dp) -> cr.apply(cnf.format(v, Math.max(0, dp)));

        assertEquals("𝟙.𝟘千", composed.format(1000, 1));
    }

    @Test
    @DisplayName("NONE 是函数式接口实例")
    void noneIsFunctionalInterface() {
        assertNotNull(ValueFormatter.NONE);
        // 验证可以作为函数式调用
        String result = ValueFormatter.NONE.format(42, 0);
        assertEquals("42", result);
    }
}
