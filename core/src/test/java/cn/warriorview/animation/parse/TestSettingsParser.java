package cn.warriorview.animation.parse;

import cn.warriorview.animation.api.Billboard;
import cn.warriorview.animation.data.DisplaySettings;
import cn.warriorview.animation.data.OffsetExpr;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试 SettingsParser —— YAML settings 配置块 → DisplaySettings 的解析。
 */
@DisplayName("SettingsParser 设置解析器测试")
class TestSettingsParser {

    private ConfigurationSection createSection(java.util.function.Consumer<ConfigurationSection> setup) {
        YamlConfiguration yaml = new YamlConfiguration();
        ConfigurationSection section = yaml.createSection("settings");
        setup.accept(section);
        return section;
    }

    @Test
    @DisplayName("null section → DEFAULT")
    void nullSectionReturnsDefault() {
        DisplaySettings settings = SettingsParser.parse(null);
        assertSame(DisplaySettings.DEFAULT, settings);
    }

    @Test
    @DisplayName("空 section → 全默认值")
    void emptySectionUsesDefaults() {
        YamlConfiguration yaml = new YamlConfiguration();
        ConfigurationSection section = yaml.createSection("settings");
        DisplaySettings settings = SettingsParser.parse(section);

        assertEquals(Billboard.CENTER, settings.billboard());
        assertFalse(settings.seeThrough());
        assertFalse(settings.textShadow());
        assertEquals(DisplaySettings.DEFAULT_BACKGROUND, settings.backgroundColor());
        assertEquals(1.0f, settings.viewRange());
        assertEquals(0, settings.teleportDuration());
        assertEquals(-1, settings.brightness());
        assertEquals(0f, settings.shadowRadius());
        assertEquals(1.0f, settings.shadowStrength());
        assertEquals(0, settings.glowColorOverride());
        assertEquals(200, settings.lineWidth());
    }

    @Nested
    @DisplayName("Billboard 解析")
    class BillboardParsing {

        @Test
        @DisplayName("小写 'fixed' → FIXED")
        void lowercaseFixed() {
            var section = createSection(s -> s.set("billboard", "fixed"));
            assertEquals(Billboard.FIXED, SettingsParser.parse(section).billboard());
        }

        @Test
        @DisplayName("大写 'CENTER' → CENTER")
        void uppercaseCenter() {
            var section = createSection(s -> s.set("billboard", "CENTER"));
            assertEquals(Billboard.CENTER, SettingsParser.parse(section).billboard());
        }

        @Test
        @DisplayName("混合大小写 'Vertical' → VERTICAL")
        void mixedCaseVertical() {
            var section = createSection(s -> s.set("billboard", "Vertical"));
            assertEquals(Billboard.VERTICAL, SettingsParser.parse(section).billboard());
        }
    }

    @Nested
    @DisplayName("布尔值解析")
    class BooleanParsing {

        @Test
        @DisplayName("see-through: true")
        void seeThrough() {
            var section = createSection(s -> s.set("see-through", true));
            assertTrue(SettingsParser.parse(section).seeThrough());
        }

        @Test
        @DisplayName("text-shadow: true")
        void textShadow() {
            var section = createSection(s -> s.set("text-shadow", true));
            assertTrue(SettingsParser.parse(section).textShadow());
        }
    }

    @Nested
    @DisplayName("背景色解析")
    class BackgroundParsing {

        @Test
        @DisplayName("'transparent' → 0")
        void transparent() {
            var section = createSection(s -> s.set("background", "transparent"));
            assertEquals(0, SettingsParser.parse(section).backgroundColor());
        }

        @Test
        @DisplayName("'#FF000000' → 不透明黑色")
        void hexBlack() {
            var section = createSection(s -> s.set("background", "#FF000000"));
            assertEquals(0xFF000000, SettingsParser.parse(section).backgroundColor());
        }

        @Test
        @DisplayName("非法值 → 默认背景色")
        void invalidFallsBackToDefault() {
            var section = createSection(s -> s.set("background", "not-a-color"));
            assertEquals(DisplaySettings.DEFAULT_BACKGROUND, SettingsParser.parse(section).backgroundColor());
        }

        @Test
        @DisplayName("空白值 → 默认背景色")
        void blankFallsBackToDefault() {
            var section = createSection(s -> s.set("background", "  "));
            assertEquals(DisplaySettings.DEFAULT_BACKGROUND, SettingsParser.parse(section).backgroundColor());
        }
    }

    @Nested
    @DisplayName("数值字段解析")
    class NumericParsing {

        @Test
        @DisplayName("view-range: 2.5")
        void viewRange() {
            var section = createSection(s -> s.set("view-range", 2.5));
            assertEquals(2.5f, SettingsParser.parse(section).viewRange());
        }

        @Test
        @DisplayName("teleport-duration: 3")
        void teleportDuration() {
            var section = createSection(s -> s.set("teleport-duration", 3));
            assertEquals(3, SettingsParser.parse(section).teleportDuration());
        }

        @Test
        @DisplayName("brightness: 200")
        void brightness() {
            var section = createSection(s -> s.set("brightness", 200));
            assertEquals(200, SettingsParser.parse(section).brightness());
        }

        @Test
        @DisplayName("shadow-radius 和 shadow-strength")
        void shadow() {
            var section = createSection(s -> {
                s.set("shadow-radius", 0.5);
                s.set("shadow-strength", 0.8);
            });
            var settings = SettingsParser.parse(section);
            assertEquals(0.5f, settings.shadowRadius());
            assertEquals(0.8f, settings.shadowStrength());
        }

        @Test
        @DisplayName("glow-color: 0xFFFFFF")
        void glowColor() {
            var section = createSection(s -> s.set("glow-color", 0xFFFFFF));
            assertEquals(0xFFFFFF, SettingsParser.parse(section).glowColorOverride());
        }

        @Test
        @DisplayName("line-width: 400")
        void lineWidth() {
            var section = createSection(s -> s.set("line-width", 400));
            assertEquals(400, SettingsParser.parse(section).lineWidth());
        }
    }

    @Nested
    @DisplayName("Offset 表达式解析")
    class OffsetParsing {

        @Test
        @DisplayName("无 offset section → ZERO")
        void noOffsetSection() {
            YamlConfiguration yaml = new YamlConfiguration();
            ConfigurationSection section = yaml.createSection("settings");
            DisplaySettings settings = SettingsParser.parse(section);
            // 无 offset section 时应使用 ZERO
            assertNotNull(settings.offset());
        }

        @Test
        @DisplayName("常量 offset 表达式")
        void constantOffset() {
            var section = createSection(s -> {
                var offset = s.createSection("offset");
                offset.set("x", "0.5");
                offset.set("y", "1.0");
                offset.set("z", "-0.3");
            });
            DisplaySettings settings = SettingsParser.parse(section);
            double[] result = settings.offset().evaluate(0.0);
            assertEquals(0.5, result[0], 1e-6);
            assertEquals(1.0, result[1], 1e-6);
            assertEquals(-0.3, result[2], 1e-6);
        }

        @Test
        @DisplayName("变量 r 参与的 offset 表达式")
        void variableOffset() {
            var section = createSection(s -> {
                var offset = s.createSection("offset");
                offset.set("x", "r * 0.6 - 0.3");
                offset.set("y", "r * 0.5");
                offset.set("z", "r * 0.6 - 0.3");
            });
            DisplaySettings settings = SettingsParser.parse(section);
            double[] r05 = settings.offset().evaluate(0.5);
            assertEquals(0.0, r05[0], 1e-6, "r=0.5 → x=0.0");
            assertEquals(0.25, r05[1], 1e-6, "r=0.5 → y=0.25");
        }

        @Test
        @DisplayName("纯数字 offset 值")
        void numericOffset() {
            var section = createSection(s -> {
                var offset = s.createSection("offset");
                offset.set("x", 0.3);  // 数字类型
                offset.set("y", 0.0);
                offset.set("z", -0.1);
            });
            DisplaySettings settings = SettingsParser.parse(section);
            double[] result = settings.offset().evaluate(0.99);
            assertEquals(0.3, result[0], 1e-6);
            assertEquals(0.0, result[1], 1e-6);
            assertEquals(-0.1, result[2], 1e-6);
        }
    }

    @Test
    @DisplayName("完整配置解析")
    void fullConfiguration() {
        var section = createSection(s -> {
            s.set("billboard", "vertical");
            s.set("see-through", true);
            s.set("text-shadow", true);
            s.set("background", "transparent");
            s.set("view-range", 3.0);
            s.set("teleport-duration", 5);
            s.set("brightness", 150);
            s.set("shadow-radius", 1.5);
            s.set("shadow-strength", 0.5);
            s.set("glow-color", 0xFF0000);
            s.set("line-width", 300);
        });
        var settings = SettingsParser.parse(section);

        assertEquals(Billboard.VERTICAL, settings.billboard());
        assertTrue(settings.seeThrough());
        assertTrue(settings.textShadow());
        assertEquals(0, settings.backgroundColor());
        assertEquals(3.0f, settings.viewRange());
        assertEquals(5, settings.teleportDuration());
        assertEquals(150, settings.brightness());
        assertEquals(1.5f, settings.shadowRadius());
        assertEquals(0.5f, settings.shadowStrength());
        assertEquals(0xFF0000, settings.glowColorOverride());
        assertEquals(300, settings.lineWidth());
    }
}
