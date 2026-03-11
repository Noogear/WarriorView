package cn.warriorview.animation.load;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试 ParamSubstitutor —— 模板参数替换器。
 * 覆盖完整值替换、内嵌插值、类型保持、嵌套结构和边界条件。
 */
@DisplayName("ParamSubstitutor 参数替换器测试")
class TestParamSubstitutor {

    private static ConfigurationSection yaml(String yamlContent) {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString(yamlContent);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return config;
    }

    // ── 完整值替换（类型保持）────────────────────────────────────────────────

    @Nested
    @DisplayName("完整值 $param 替换")
    class ExactMatchTests {

        @Test
        @DisplayName("$param → Number 保持 Double 类型")
        void numberTypePreserved() {
            ConfigurationSection section = yaml("size: $peak_size");
            ParamSubstitutor.substitute(section, Map.of("peak_size", 1.5));
            Object result = section.get("size");
            assertInstanceOf(Double.class, result);
            assertEquals(1.5, (Double) result, 1e-9);
        }

        @Test
        @DisplayName("$param → Integer 保持 Integer 类型")
        void integerTypePreserved() {
            ConfigurationSection section = yaml("duration: $dur");
            ParamSubstitutor.substitute(section, Map.of("dur", 20));
            Object result = section.get("duration");
            assertInstanceOf(Integer.class, result);
            assertEquals(20, (Integer) result);
        }

        @Test
        @DisplayName("$param → String 保持 String 类型")
        void stringTypePreserved() {
            ConfigurationSection section = yaml("billboard: $mode");
            ParamSubstitutor.substitute(section, Map.of("mode", "center"));
            assertEquals("center", section.getString("billboard"));
        }

        @Test
        @DisplayName("未知参数保留原文 $unknown")
        void unknownParamRetainedExact() {
            ConfigurationSection section = yaml("size: $unknown");
            ParamSubstitutor.substitute(section, Map.of("peak_size", 1.5));
            assertEquals("$unknown", section.getString("size"));
        }
    }

    // ── 内嵌插值 ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("内嵌字符串插值")
    class InlineTests {

        @Test
        @DisplayName("\"sin(t * $speed)\" → \"sin(t * 0.3)\"")
        void mathExpressionInterpolation() {
            ConfigurationSection section = yaml("x: \"sin(t * $speed)\"");
            ParamSubstitutor.substitute(section, Map.of("speed", 0.3));
            assertEquals("sin(t * 0.3)", section.getString("x"));
        }

        @Test
        @DisplayName("多个 $param 同时替换")
        void multipleParamsInOneString() {
            ConfigurationSection section = yaml("x: \"sin(t * $speed) * $radius\"");
            ParamSubstitutor.substitute(section, Map.of("speed", 0.3, "radius", 0.5));
            assertEquals("sin(t * 0.3) * 0.5", section.getString("x"));
        }

        @Test
        @DisplayName("\"r * $spread - $spread / 2\" 同一参数多次出现")
        void sameParamMultipleTimes() {
            ConfigurationSection section = yaml("x: \"r * $spread - $spread / 2\"");
            ParamSubstitutor.substitute(section, Map.of("spread", 0.6));
            assertEquals("r * 0.6 - 0.6 / 2", section.getString("x"));
        }

        @Test
        @DisplayName("未知参数保留原文")
        void unknownParamRetainedInline() {
            ConfigurationSection section = yaml("x: \"$known + $unknown\"");
            ParamSubstitutor.substitute(section, Map.of("known", 1.0));
            assertEquals("1.0 + $unknown", section.getString("x"));
        }

        @Test
        @DisplayName("无 $ 的字符串不受影响")
        void noParamStringUnchanged() {
            ConfigurationSection section = yaml("x: \"sin(t * 0.3)\"");
            ParamSubstitutor.substitute(section, Map.of("speed", 0.3));
            assertEquals("sin(t * 0.3)", section.getString("x"));
        }
    }

    // ── 嵌套结构 ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("嵌套结构替换")
    class NestedTests {

        @Test
        @DisplayName("嵌套 ConfigurationSection 中的 $param")
        void nestedConfigSection() {
            ConfigurationSection section = yaml("""
                    settings:
                      view-range: $vr
                      offset:
                        x: "r * $spread"
                    """);
            ParamSubstitutor.substitute(section, Map.of("vr", 32, "spread", 0.5));
            ConfigurationSection settings = section.getConfigurationSection("settings");
            assertNotNull(settings);
            assertEquals(32, settings.getInt("view-range"));
            assertEquals("r * 0.5", settings.getConfigurationSection("offset").getString("x"));
        }

        @Test
        @DisplayName("List<Map>（timeline 场景）中的 $param")
        void listOfMapsTimeline() {
            ConfigurationSection section = yaml("""
                    timeline:
                      - time: 0
                        size: 1.0
                      - duration: $dur
                        size: $peak
                        opacity: 0%
                    """);
            ParamSubstitutor.substitute(section, Map.of("dur", 10, "peak", 2.0));
            List<?> timeline = section.getList("timeline");
            assertNotNull(timeline);
            assertEquals(2, timeline.size());

            @SuppressWarnings("unchecked")
            Map<String, Object> frame1 = (Map<String, Object>) timeline.get(1);
            assertEquals(10, frame1.get("duration"));
            assertEquals(2.0, frame1.get("size"));
            assertEquals("0%", frame1.get("opacity")); // no $param → unchanged
        }
    }

    // ── 边界条件 ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("边界条件")
    class EdgeCaseTests {

        @Test
        @DisplayName("空参数 map → 无替换")
        void emptyParams() {
            ConfigurationSection section = yaml("size: $peak");
            ParamSubstitutor.substitute(section, Map.of());
            assertEquals("$peak", section.getString("size"));
        }

        @Test
        @DisplayName("Boolean 和 null 值不受影响")
        void booleanAndNullUnchanged() {
            ConfigurationSection section = yaml("""
                    flag: true
                    text-shadow: false
                    """);
            ParamSubstitutor.substitute(section, Map.of("flag", 999));
            assertTrue(section.getBoolean("flag")); // Boolean, not a $param string
        }

        @Test
        @DisplayName("数字值不受替换影响（非字符串）")
        void numericValuesUntouched() {
            ConfigurationSection section = yaml("dur: 15");
            ParamSubstitutor.substitute(section, Map.of("dur", 999));
            assertEquals(15, section.getInt("dur")); // literal int, not "$dur"
        }

        @Test
        @DisplayName("$param 名仅匹配 [a-zA-Z_][a-zA-Z0-9_]* 格式")
        void paramNameValidation() {
            ConfigurationSection section = yaml("x: \"$123invalid\"");
            ParamSubstitutor.substitute(section, Map.of("123invalid", 999));
            assertEquals("$123invalid", section.getString("x")); // $ 后跟数字不匹配
        }

        @Test
        @DisplayName("嵌套列表中的纯数字列表不受影响")
        void numericListUntouched() {
            ConfigurationSection section = yaml("translation: [0.0, 0.5, 0.0]");
            ParamSubstitutor.substitute(section, Map.of("x", 999));
            List<?> list = section.getList("translation");
            assertNotNull(list);
            assertEquals(3, list.size());
            assertEquals(0.5, ((Number) list.get(1)).doubleValue(), 1e-9);
        }
    }
}
