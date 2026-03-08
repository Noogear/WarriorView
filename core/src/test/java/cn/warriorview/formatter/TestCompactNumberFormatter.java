package cn.warriorview.formatter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 全面测试 CompactNumberFormatter —— 紧凑型数字格式化器。
 * 覆盖量化缩写、精度、边界值、VT 缓存和特殊浮点数。
 */
@DisplayName("CompactNumberFormatter 紧凑数字格式化测试")
class TestCompactNumberFormatter {

    private CompactNumberFormatter createKMB() {
        Map<Double, String> config = new LinkedHashMap<>();
        config.put(1000.0, "K");
        config.put(1_000_000.0, "M");
        config.put(1_000_000_000.0, "B");
        return CompactNumberFormatter.of(config);
    }

    // ── 基本格式化 ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("基本数字格式化")
    class BasicFormatting {

        @Test
        @DisplayName("小于阈值的数字不缩写")
        void belowThreshold() {
            CompactNumberFormatter f = createKMB();
            assertEquals("999", f.format(999, 0));
            assertEquals("500", f.format(500, 0));
            assertEquals("1", f.format(1, 0));
        }

        @Test
        @DisplayName("恰好达到阈值时缩写")
        void atThreshold() {
            CompactNumberFormatter f = createKMB();
            assertEquals("1.0K", f.format(1000, 1));
            assertEquals("1.0M", f.format(1_000_000, 1));
            assertEquals("1.0B", f.format(1_000_000_000, 1));
        }

        @ParameterizedTest
        @CsvSource({
                "1234,   1, 1.2K",
                "1500,   1, 1.5K",
                "12345,  1, 12.3K",
                "123456, 1, 123.5K",
                "999999, 1, 1000.0K",
        })
        @DisplayName("K 级别缩写")
        void kFormatting(double value, int precision, String expected) {
            assertEquals(expected, createKMB().format(value, precision));
        }

        @ParameterizedTest
        @CsvSource({
                "1500000,  1, 1.5M",
                "2500000,  2, 2.50M",
                "999999999, 1, 1000.0M",
        })
        @DisplayName("M 级别缩写")
        void mFormatting(double value, int precision, String expected) {
            assertEquals(expected, createKMB().format(value, precision));
        }

        @Test
        @DisplayName("B 级别缩写")
        void bFormatting() {
            CompactNumberFormatter f = createKMB();
            assertEquals("1.5B", f.format(1_500_000_000, 1));
        }
    }

    // ── 精度控制 ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("精度控制")
    class Precision {

        @Test
        @DisplayName("精度 0：无小数位")
        void zeroPrecision() {
            assertEquals("1K", createKMB().format(1000, 0));
            assertEquals("2K", createKMB().format(1500, 0));
        }

        @Test
        @DisplayName("精度 1：一位小数")
        void onePrecision() {
            assertEquals("1.5K", createKMB().format(1500, 1));
        }

        @Test
        @DisplayName("精度 2：两位小数")
        void twoPrecision() {
            assertEquals("1.50K", createKMB().format(1500, 2));
        }

        @Test
        @DisplayName("精度 3：三位小数")
        void threePrecision() {
            assertEquals("1.235K", createKMB().format(1234.5, 3));
        }

        @Test
        @DisplayName("低于阈值的数字也受精度控制")
        void belowThresholdWithPrecision() {
            assertEquals("123.46", createKMB().format(123.456, 2));
        }

        @Test
        @DisplayName("负精度抛出异常")
        void negativePrecisionThrows() {
            assertThrows(IllegalArgumentException.class,
                    () -> createKMB().format(100, -1));
        }
    }

    // ── 边界值与特殊数字 ────────────────────────────────────────────────────

    @Nested
    @DisplayName("特殊浮点数")
    class SpecialValues {

        @Test
        @DisplayName("NaN 返回 'NaN'")
        void nanValue() {
            assertEquals("NaN", createKMB().format(Double.NaN, 1));
        }

        @Test
        @DisplayName("+Infinity 返回 'Infinity'")
        void positiveInfinity() {
            assertEquals("Infinity", createKMB().format(Double.POSITIVE_INFINITY, 1));
        }

        @Test
        @DisplayName("-Infinity 返回 '-Infinity'")
        void negativeInfinity() {
            assertEquals("-Infinity", createKMB().format(Double.NEGATIVE_INFINITY, 1));
        }

        @Test
        @DisplayName("零值")
        void zero() {
            assertEquals("0", createKMB().format(0, 0));
            assertEquals("0.00", createKMB().format(0, 2));
        }

        @Test
        @DisplayName("负数处理")
        void negativeNumbers() {
            assertEquals("-1.5K", createKMB().format(-1500, 1));
            assertEquals("-100", createKMB().format(-100, 0));
        }

        @Test
        @DisplayName("非常小的正数")
        void verySmallPositive() {
            assertEquals("0.00", createKMB().format(0.001, 2));
        }
    }

    // ── 空配置 ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("空配置（无阈值规则）")
    class EmptyConfig {

        @Test
        @DisplayName("null 配置 → 无缩写")
        void nullConfig() {
            CompactNumberFormatter f = CompactNumberFormatter.of(null);
            assertEquals("12345", f.format(12345, 0));
            assertEquals("12345.00", f.format(12345, 2));
        }

        @Test
        @DisplayName("空 Map 配置 → 无缩写")
        void emptyMapConfig() {
            CompactNumberFormatter f = CompactNumberFormatter.of(Map.of());
            assertEquals("12345", f.format(12345, 0));
        }
    }

    // ── Builder ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("Builder 构建与直接构建结果一致")
        void builderProducesSameResult() {
            CompactNumberFormatter fDirect = createKMB();
            CompactNumberFormatter fBuilder = new CompactNumberFormatter.Builder()
                    .add(1000, "K")
                    .add(1_000_000, "M")
                    .add(1_000_000_000, "B")
                    .build();
            assertEquals(fDirect.format(1500, 1), fBuilder.format(1500, 1));
            assertEquals(fDirect.format(2_500_000, 2), fBuilder.format(2_500_000, 2));
        }

        @Test
        @DisplayName("Builder 忽略非法阈值")
        void builderIgnoresInvalid() {
            CompactNumberFormatter f = new CompactNumberFormatter.Builder()
                    .add(0, "X")      // 非正阈值
                    .add(-100, "Y")   // 负阈值
                    .add(1000, null)  // null 符号
                    .add(2000, "K")   // 唯一合法项
                    .build();
            assertEquals("1.0K", f.format(2000, 1));
            assertEquals("1999", f.format(1999, 0));
        }
    }
}
