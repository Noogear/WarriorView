package cn.warriorview.animation.data;

import cn.warriorview.animation.api.Billboard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 全面测试动画数据记录类型：TransformSnapshot、BakedFrame、BakedSequence、DisplaySettings。
 * 验证不变性、默认值、相等性和边界条件。
 */
@DisplayName("动画数据记录测试")
class TestDataRecords {

    // ── TransformSnapshot ────────────────────────────────────────────────────

    @Nested
    @DisplayName("TransformSnapshot")
    class TransformSnapshotTests {

        @Test
        @DisplayName("IDENTITY 常量的默认值正确")
        void identityConstant() {
            TransformSnapshot id = TransformSnapshot.IDENTITY;
            // 平移为零
            assertEquals(0f, id.tx());
            assertEquals(0f, id.ty());
            assertEquals(0f, id.tz());
            // 缩放为 1
            assertEquals(1f, id.sx());
            assertEquals(1f, id.sy());
            assertEquals(1f, id.sz());
            // 左旋转为单位四元数 (0,0,0,1)
            assertEquals(0f, id.lrx());
            assertEquals(0f, id.lry());
            assertEquals(0f, id.lrz());
            assertEquals(1f, id.lrw());
            // 右旋转为单位四元数 (0,0,0,1)
            assertEquals(0f, id.rrx());
            assertEquals(0f, id.rry());
            assertEquals(0f, id.rrz());
            assertEquals(1f, id.rrw());
            // 不透明度默认 -1
            assertEquals((byte) -1, id.textOpacity());
        }

        @Test
        @DisplayName("Record equals：相同值相等")
        void equalityWithSameValues() {
            var a = new TransformSnapshot(1f, 2f, 3f, 4f, 5f, 6f,
                    0.1f, 0.2f, 0.3f, 0.9f, 0.4f, 0.5f, 0.6f, 0.8f, (byte) 100);
            var b = new TransformSnapshot(1f, 2f, 3f, 4f, 5f, 6f,
                    0.1f, 0.2f, 0.3f, 0.9f, 0.4f, 0.5f, 0.6f, 0.8f, (byte) 100);
            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
        }

        @Test
        @DisplayName("Record equals：不同值不相等（用于零增量帧去重）")
        void inequalityWithDifferentValues() {
            var base = TransformSnapshot.IDENTITY;
            // 仅改变 ty
            var moved = new TransformSnapshot(0f, 0.5f, 0f, 1f, 1f, 1f,
                    0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, (byte) -1);
            assertNotEquals(base, moved);
        }

        @Test
        @DisplayName("Record equals：不透明度差异应导致不相等")
        void opacityDifferenceMeansNotEqual() {
            var a = new TransformSnapshot(0f, 0f, 0f, 1f, 1f, 1f,
                    0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, (byte) 50);
            var b = new TransformSnapshot(0f, 0f, 0f, 1f, 1f, 1f,
                    0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, (byte) 127);
            assertNotEquals(a, b);
        }

        @Test
        @DisplayName("IDENTITY 自身与自身相等")
        void identityEqualsSelf() {
            assertEquals(TransformSnapshot.IDENTITY, TransformSnapshot.IDENTITY);
        }

        @Test
        @DisplayName("所有 15 个字段都能通过访问器读取")
        void allFieldsAccessible() {
            var s = new TransformSnapshot(
                    1.1f, 2.2f, 3.3f, 4.4f, 5.5f, 6.6f,
                    0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f, (byte) 42);
            assertEquals(1.1f, s.tx());
            assertEquals(2.2f, s.ty());
            assertEquals(3.3f, s.tz());
            assertEquals(4.4f, s.sx());
            assertEquals(5.5f, s.sy());
            assertEquals(6.6f, s.sz());
            assertEquals(0.1f, s.lrx());
            assertEquals(0.2f, s.lry());
            assertEquals(0.3f, s.lrz());
            assertEquals(0.4f, s.lrw());
            assertEquals(0.5f, s.rrx());
            assertEquals(0.6f, s.rry());
            assertEquals(0.7f, s.rrz());
            assertEquals(0.8f, s.rrw());
            assertEquals((byte) 42, s.textOpacity());
        }
    }

    // ── BakedFrame ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("BakedFrame")
    class BakedFrameTests {

        @Test
        @DisplayName("构造与字段读取")
        void constructAndRead() {
            var snap = TransformSnapshot.IDENTITY;
            var frame = new BakedFrame(10, 0, 5, snap);
            assertEquals(10, frame.tickOffset());
            assertEquals(0, frame.interpolationDelay());
            assertEquals(5, frame.interpolationTicks());
            assertSame(snap, frame.snapshot());
        }

        @Test
        @DisplayName("相同字段的帧相等")
        void equalFrames() {
            var snap = TransformSnapshot.IDENTITY;
            var a = new BakedFrame(0, 0, 1, snap);
            var b = new BakedFrame(0, 0, 1, snap);
            assertEquals(a, b);
        }

        @Test
        @DisplayName("tick 不同的帧不相等")
        void differentTickOffset() {
            var snap = TransformSnapshot.IDENTITY;
            var a = new BakedFrame(0, 0, 1, snap);
            var b = new BakedFrame(5, 0, 1, snap);
            assertNotEquals(a, b);
        }

        @Test
        @DisplayName("零 tick 帧有效（初始帧）")
        void zeroTickFrame() {
            var frame = new BakedFrame(0, 0, 0, TransformSnapshot.IDENTITY);
            assertEquals(0, frame.tickOffset());
            assertEquals(0, frame.interpolationTicks());
        }
    }

    // ── BakedSequence ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("BakedSequence")
    class BakedSequenceTests {

        @Test
        @DisplayName("空帧数组的序列")
        void emptySequence() {
            var seq = new BakedSequence(new BakedFrame[0], 0, DisplaySettings.DEFAULT);
            assertEquals(0, seq.frames().length);
            assertEquals(0, seq.totalTicks());
        }

        @Test
        @DisplayName("单帧序列")
        void singleFrameSequence() {
            var frame = new BakedFrame(0, 0, 0, TransformSnapshot.IDENTITY);
            var seq = new BakedSequence(new BakedFrame[]{frame}, 0, DisplaySettings.DEFAULT);
            assertEquals(1, seq.frames().length);
            assertSame(frame, seq.frames()[0]);
        }

        @Test
        @DisplayName("多帧序列保持排序")
        void multiFrameOrdering() {
            var f0 = new BakedFrame(0, 0, 0, TransformSnapshot.IDENTITY);
            var f1 = new BakedFrame(10, 0, 10, TransformSnapshot.IDENTITY);
            var f2 = new BakedFrame(20, 0, 10, TransformSnapshot.IDENTITY);
            var seq = new BakedSequence(new BakedFrame[]{f0, f1, f2}, 20, DisplaySettings.DEFAULT);
            assertEquals(3, seq.frames().length);
            assertEquals(20, seq.totalTicks());
            assertEquals(0, seq.frames()[0].tickOffset());
            assertEquals(10, seq.frames()[1].tickOffset());
            assertEquals(20, seq.frames()[2].tickOffset());
        }

        @Test
        @DisplayName("settings 引用保持不变")
        void settingsReferencePreserved() {
            var settings = DisplaySettings.DEFAULT;
            var seq = new BakedSequence(new BakedFrame[0], 0, settings);
            assertSame(settings, seq.settings());
        }
    }

    // ── DisplaySettings ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("DisplaySettings")
    class DisplaySettingsTests {

        @Test
        @DisplayName("DEFAULT 常量的默认值正确")
        void defaultConstant() {
            var def = DisplaySettings.DEFAULT;
            assertEquals(Billboard.CENTER, def.billboard());
            assertFalse(def.seeThrough());
            assertFalse(def.textShadow());
            assertEquals(0x40000000, def.backgroundColor());
            assertEquals(1.0f, def.viewRange());
            assertEquals(0, def.teleportDuration());
            assertEquals(-1, def.brightness());
            assertEquals(0f, def.shadowRadius());
            assertEquals(1.0f, def.shadowStrength());
            assertEquals(0, def.glowColorOverride());
            assertEquals(200, def.lineWidth());
            assertSame(OffsetExpr.ZERO, def.offset());
        }

        @Test
        @DisplayName("DEFAULT_BACKGROUND 常量正确")
        void defaultBackgroundConstant() {
            assertEquals(0x40000000, DisplaySettings.DEFAULT_BACKGROUND);
        }

        @Test
        @DisplayName("自定义 DisplaySettings 字段完整")
        void customSettings() {
            var s = new DisplaySettings(
                    Billboard.FIXED, true, true, 0xFF000000,
                    2.0f, 5, 100, 0.5f, 0.8f, 0xFFFFFF, 300, OffsetExpr.ZERO);
            assertEquals(Billboard.FIXED, s.billboard());
            assertTrue(s.seeThrough());
            assertTrue(s.textShadow());
            assertEquals(0xFF000000, s.backgroundColor());
            assertEquals(2.0f, s.viewRange());
            assertEquals(5, s.teleportDuration());
            assertEquals(100, s.brightness());
            assertEquals(0.5f, s.shadowRadius());
            assertEquals(0.8f, s.shadowStrength());
            assertEquals(0xFFFFFF, s.glowColorOverride());
            assertEquals(300, s.lineWidth());
        }
    }
}
