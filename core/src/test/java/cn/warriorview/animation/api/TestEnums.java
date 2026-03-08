package cn.warriorview.animation.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试 Billboard 和 AnimationType 枚举。
 */
@DisplayName("动画 API 枚举测试")
class TestEnums {

    // ── Billboard ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Billboard")
    class BillboardTests {

        @Test
        @DisplayName("枚举包含所有四种模式")
        void allModes() {
            assertEquals(4, Billboard.values().length);
        }

        @ParameterizedTest
        @CsvSource({"FIXED,0", "VERTICAL,1", "HORIZONTAL,2", "CENTER,3"})
        @DisplayName("protocolId 映射正确")
        void protocolIds(String name, int expectedId) {
            Billboard b = Billboard.valueOf(name);
            assertEquals((byte) expectedId, b.protocolId());
        }

        @ParameterizedTest
        @CsvSource({
                "fixed,FIXED",
                "FIXED,FIXED",
                "Fixed,FIXED",
                "vertical,VERTICAL",
                "VERTICAL,VERTICAL",
                "horizontal,HORIZONTAL",
                "center,CENTER",
                "CENTER,CENTER"
        })
        @DisplayName("fromName 大小写不敏感解析")
        void fromNameCaseInsensitive(String input, String expected) {
            assertEquals(Billboard.valueOf(expected), Billboard.fromName(input, Billboard.CENTER));
        }

        @ParameterizedTest
        @ValueSource(strings = {"unknown", "INVALID", "", "  "})
        @DisplayName("fromName 未知值返回 fallback")
        void fromNameUnknownReturnsFallback(String input) {
            assertEquals(Billboard.FIXED, Billboard.fromName(input, Billboard.FIXED));
            assertEquals(Billboard.CENTER, Billboard.fromName(input, Billboard.CENTER));
        }

        @Test
        @DisplayName("fromName null 返回 fallback")
        void fromNameNullReturnsFallback() {
            assertEquals(Billboard.VERTICAL, Billboard.fromName(null, Billboard.VERTICAL));
        }
    }

    // ── AnimationType ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("AnimationType")
    class AnimationTypeTests {

        @Test
        @DisplayName("枚举包含所有三种类型")
        void allTypes() {
            assertEquals(3, AnimationType.values().length);
        }

        @Test
        @DisplayName("枚举值存在")
        void valuesExist() {
            assertNotNull(AnimationType.KEYFRAME);
            assertNotNull(AnimationType.EQUATION);
            assertNotNull(AnimationType.PRESET);
        }

        @Test
        @DisplayName("valueOf 反向解析")
        void valueOfWorks() {
            assertEquals(AnimationType.KEYFRAME, AnimationType.valueOf("KEYFRAME"));
            assertEquals(AnimationType.EQUATION, AnimationType.valueOf("EQUATION"));
            assertEquals(AnimationType.PRESET, AnimationType.valueOf("PRESET"));
        }
    }
}
