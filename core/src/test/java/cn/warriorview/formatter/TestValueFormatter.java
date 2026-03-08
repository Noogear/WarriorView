package cn.warriorview.formatter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试 ValueFormatter 函数式接口及 NONE 默认实现。
 */
@DisplayName("ValueFormatter 数值格式化器测试")
class TestValueFormatter {

    // ── NONE 格式化器（整数） ───────────────────────────────────────────────

    @Test
    @DisplayName("NONE → 整数格式")
    void noneIntegerFormat() {
        assertEquals("123", ValueFormatter.NONE.format(123.456));
        assertEquals("0", ValueFormatter.NONE.format(0));
        assertEquals("-5", ValueFormatter.NONE.format(-5.9));
    }

    // ── decimal() 工厂方法 ──────────────────────────────────────────────────

    @Test
    @DisplayName("decimal(1) → 一位小数")
    void decimalOneDecimal() {
        assertEquals("123.5", ValueFormatter.decimal(1).format(123.456));
    }

    @Test
    @DisplayName("decimal(2) → 两位小数")
    void decimalTwoDecimals() {
        assertEquals("123.46", ValueFormatter.decimal(2).format(123.456));
    }

    @Test
    @DisplayName("decimal(3) → 三位小数")
    void decimalThreeDecimals() {
        assertEquals("123.456", ValueFormatter.decimal(3).format(123.456));
    }

    @Test
    @DisplayName("decimal(≤0) 回退到 NONE")
    void decimalNegativePrecision() {
        assertEquals("123", ValueFormatter.decimal(-5).format(123.456));
        assertEquals("123", ValueFormatter.decimal(0).format(123.456));
    }

    @Test
    @DisplayName("decimal(2) 零值")
    void decimalZeroValue() {
        assertEquals("0", ValueFormatter.NONE.format(0));
        assertEquals("0.00", ValueFormatter.decimal(2).format(0));
    }

    @Test
    @DisplayName("NONE 大数值")
    void noneLargeValue() {
        assertEquals("1000000", ValueFormatter.NONE.format(1_000_000));
    }

    // ── 自定义 Lambda 实现 ──────────────────────────────────────────────────

    @Test
    @DisplayName("Lambda 实现：固定后缀")
    void lambdaWithSuffix() {
        ValueFormatter custom = value -> String.format("%.0f HP", value);
        assertEquals("100 HP", custom.format(100));
    }

    @Test
    @DisplayName("Lambda 实现：乘法预处理")
    void lambdaMultiplied() {
        ValueFormatter doubled = value -> String.format("%.1f", value * 2);
        assertEquals("20.0", doubled.format(10.0));
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

        final int p = 1;
        ValueFormatter composed = v -> cr.apply(cnf.format(v, p));

        assertEquals("𝟙.𝟘千", composed.format(1000));
    }

    @Test
    @DisplayName("NONE 是函数式接口实例")
    void noneIsFunctionalInterface() {
        assertNotNull(ValueFormatter.NONE);
        // 验证可以作为函数式调用
        String result = ValueFormatter.NONE.format(42);
        assertEquals("42", result);
    }
}
