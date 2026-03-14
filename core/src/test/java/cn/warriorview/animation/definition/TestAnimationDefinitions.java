package cn.warriorview.animation.definition;

import cn.warriorview.animation.api.AnimationType;
import cn.warriorview.animation.api.Space;
import cn.warriorview.animation.data.*;
import gloomlib.math.api.MathEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 全面测试 AnimationDef 密封层次结构：KeyframeDef、EquationDef、PresetDef。
 * 覆盖类型判别、bake 行为和共享/独占语义。
 */
@DisplayName("AnimationDef 定义层次结构测试")
class TestAnimationDefinitions {

    // ── KeyframeDef ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("KeyframeDef")
    class KeyframeDefTests {

        private KeyframeDef createSimple(String name) {
            var snap0 = TransformSnapshot.IDENTITY;
            var snap1 = new TransformSnapshot(0f, 0.5f, 0f, 1f, 1f, 1f,
                    0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, (byte) -1);
            var f0 = new BakedFrame(0, 0, 0, snap0);
            var f1 = new BakedFrame(10, 0, 10, snap1);
            var seq = new BakedSequence(new BakedFrame[]{f0, f1}, 10, DisplaySettings.DEFAULT);
            return new KeyframeDef(name, DisplaySettings.DEFAULT, Space.WORLD, seq);
        }

        @Test
        @DisplayName("type() 返回 KEYFRAME")
        void type() {
            assertEquals(AnimationType.KEYFRAME, createSimple("test").type());
        }

        @Test
        @DisplayName("name() 返回注册名称")
        void name() {
            assertEquals("my_anim", createSimple("my_anim").name());
        }

        @Test
        @DisplayName("totalDurationTicks() 返回序列总 tick 数")
        void totalDuration() {
            assertEquals(10, createSimple("test").totalDurationTicks());
        }

        @Test
        @DisplayName("bake() 返回共享序列引用（零拷贝）")
        void bakeReturnsSharedSequence() {
            var def = createSimple("test");
            BakedSequence s1 = def.bake(0.0);
            BakedSequence s2 = def.bake(0.5);
            BakedSequence s3 = def.bake(0.99);
            // 所有 bake 调用应返回同一对象引用
            assertSame(s1, s2, "Keyframe bake 应返回共享引用");
            assertSame(s2, s3, "Keyframe bake 应返回共享引用");
            assertSame(def.baked(), s1, "bake 应返回预烘焙序列");
        }

        @Test
        @DisplayName("bake() 忽略 r 参数")
        void bakeIgnoresR() {
            var def = createSimple("test");
            var a = def.bake(0.0);
            var b = def.bake(1.0);
            assertSame(a, b);
        }

        @Test
        @DisplayName("settings() 返回 DisplaySettings")
        void settings() {
            var def = createSimple("test");
            assertSame(DisplaySettings.DEFAULT, def.settings());
        }
    }

    // ── EquationDef ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("EquationDef")
    class EquationDefTests {

        private EquationDef createLinearRise() {
            return new EquationDef(
                    "linear_rise", DisplaySettings.DEFAULT, Space.WORLD,
                    20, 1,
                    MathEngine.compile("0", EquationDef.VARS),           // posX
                    MathEngine.compile("t * 0.05", EquationDef.VARS),    // posY
                    MathEngine.compile("0", EquationDef.VARS),           // posZ
                    MathEngine.compile("1", EquationDef.VARS),           // scaleX
                    MathEngine.compile("1", EquationDef.VARS),           // scaleY
                    MathEngine.compile("1", EquationDef.VARS),           // scaleZ
                    MathEngine.compile("0", EquationDef.VARS),           // rotX
                    MathEngine.compile("0", EquationDef.VARS),           // rotY
                    MathEngine.compile("0", EquationDef.VARS),           // rotZ
                    MathEngine.compile("127", EquationDef.VARS)          // opacity
            );
        }

        @Test
        @DisplayName("type() 返回 EQUATION")
        void type() {
            assertEquals(AnimationType.EQUATION, createLinearRise().type());
        }

        @Test
        @DisplayName("totalDurationTicks() 返回持续时间")
        void totalDuration() {
            assertEquals(20, createLinearRise().totalDurationTicks());
        }

        @Test
        @DisplayName("bake() 生成正确帧数")
        void bakeFrameCount() {
            var def = createLinearRise();
            BakedSequence seq = def.bake(0.0);
            // count = max(1, 20/1 + 1) = 21
            assertEquals(21, seq.frames().length);
            assertEquals(20, seq.totalTicks());
        }

        @Test
        @DisplayName("bake() 不同 r 值生成不同实例")
        void bakeCreatesNewInstances() {
            var def = createLinearRise();
            BakedSequence s1 = def.bake(0.0);
            BakedSequence s2 = def.bake(0.5);
            // 不同的实例（share-nothing）
            assertNotSame(s1, s2, "Equation bake 应每次创建新实例");
        }

        @Test
        @DisplayName("bake() posY 随 t 线性增长")
        void bakePositionIncreasesOverTime() {
            var def = createLinearRise();
            BakedSequence seq = def.bake(0.0);
            BakedFrame[] frames = seq.frames();

            // 第 0 帧：t=0, posY=0
            assertEquals(0f, frames[0].snapshot().ty(), 1e-5f);
            // 第 10 帧：t=10, posY=0.5
            assertEquals(0.5f, frames[10].snapshot().ty(), 1e-5f);
            // 第 20 帧：t=20, posY=1.0
            assertEquals(1.0f, frames[20].snapshot().ty(), 1e-5f);
        }

        @Test
        @DisplayName("bake() tickOffset: frame[0]=0, frame[i]=START of segment")
        void bakeTickOffsets() {
            var def = new EquationDef(
                    "sampled", DisplaySettings.DEFAULT, Space.WORLD,
                    10, 2,    // sampleInterval=2
                    MathEngine.compile("0", EquationDef.VARS),
                    MathEngine.compile("0", EquationDef.VARS),
                    MathEngine.compile("0", EquationDef.VARS),
                    MathEngine.compile("1", EquationDef.VARS),
                    MathEngine.compile("1", EquationDef.VARS),
                    MathEngine.compile("1", EquationDef.VARS),
                    MathEngine.compile("0", EquationDef.VARS),
                    MathEngine.compile("0", EquationDef.VARS),
                    MathEngine.compile("0", EquationDef.VARS),
                    MathEngine.compile("127", EquationDef.VARS)
            );
            BakedSequence seq = def.bake(0.0);
            BakedFrame[] frames = seq.frames();
            // count = max(1, 10/2+1) = 6
            assertEquals(6, frames.length);
            // frame[0] = initial state at spawn (tick 0)
            // frame[i>0] = target sent at start of segment = (i-1)*sampleInterval
            assertEquals(0, frames[0].tickOffset(), "Frame 0 (initial)");
            assertEquals(0, frames[1].tickOffset(), "Frame 1 (first target, sent at spawn)");
            assertEquals(2, frames[2].tickOffset(), "Frame 2");
            assertEquals(4, frames[3].tickOffset(), "Frame 3");
            assertEquals(6, frames[4].tickOffset(), "Frame 4");
            assertEquals(8, frames[5].tickOffset(), "Frame 5");
        }

        @Test
        @DisplayName("bake() r 变量影响位置")
        void bakeWithRandVariable() {
            var def = new EquationDef(
                    "random", DisplaySettings.DEFAULT, Space.WORLD,
                    5, 1,
                    MathEngine.compile("r * 2", EquationDef.VARS),  // posX depends on r
                    MathEngine.compile("0", EquationDef.VARS),
                    MathEngine.compile("0", EquationDef.VARS),
                    MathEngine.compile("1", EquationDef.VARS),
                    MathEngine.compile("1", EquationDef.VARS),
                    MathEngine.compile("1", EquationDef.VARS),
                    MathEngine.compile("0", EquationDef.VARS),
                    MathEngine.compile("0", EquationDef.VARS),
                    MathEngine.compile("0", EquationDef.VARS),
                    MathEngine.compile("127", EquationDef.VARS)
            );
            BakedSequence seqA = def.bake(0.0);
            BakedSequence seqB = def.bake(0.5);

            assertEquals(0.0f, seqA.frames()[0].snapshot().tx(), 1e-5f, "r=0 → tx=0");
            assertEquals(1.0f, seqB.frames()[0].snapshot().tx(), 1e-5f, "r=0.5 → tx=1.0");
        }

        @Test
        @DisplayName("bake() 不透明度表达式正确")
        void bakeOpacity() {
            var def = new EquationDef(
                    "fading", DisplaySettings.DEFAULT, Space.WORLD,
                    10, 1,
                    MathEngine.compile("0", EquationDef.VARS),
                    MathEngine.compile("0", EquationDef.VARS),
                    MathEngine.compile("0", EquationDef.VARS),
                    MathEngine.compile("1", EquationDef.VARS),
                    MathEngine.compile("1", EquationDef.VARS),
                    MathEngine.compile("1", EquationDef.VARS),
                    MathEngine.compile("0", EquationDef.VARS),
                    MathEngine.compile("0", EquationDef.VARS),
                    MathEngine.compile("0", EquationDef.VARS),
                    MathEngine.compile("127 - t * 10", EquationDef.VARS)  // 递减不透明度
            );
            BakedSequence seq = def.bake(0.0);
            assertEquals((byte) 127, seq.frames()[0].snapshot().textOpacity());
            assertEquals((byte) 117, seq.frames()[1].snapshot().textOpacity());
            assertEquals((byte) 27, seq.frames()[10].snapshot().textOpacity());
        }

        @Test
        @DisplayName("bake() 负不透明度映射为 -1（默认）")
        void bakeNegativeOpacity() {
            var def = new EquationDef(
                    "neg_op", DisplaySettings.DEFAULT, Space.WORLD,
                    1, 1,
                    MathEngine.compile("0", EquationDef.VARS),
                    MathEngine.compile("0", EquationDef.VARS),
                    MathEngine.compile("0", EquationDef.VARS),
                    MathEngine.compile("1", EquationDef.VARS),
                    MathEngine.compile("1", EquationDef.VARS),
                    MathEngine.compile("1", EquationDef.VARS),
                    MathEngine.compile("0", EquationDef.VARS),
                    MathEngine.compile("0", EquationDef.VARS),
                    MathEngine.compile("0", EquationDef.VARS),
                    MathEngine.compile("-1", EquationDef.VARS)
            );
            BakedSequence seq = def.bake(0.0);
            assertEquals((byte) -1, seq.frames()[0].snapshot().textOpacity());
        }

        @Test
        @DisplayName("bake() 欧拉角转四元数正确（90度旋转）")
        void bakeEulerToQuaternion() {
            var def = new EquationDef(
                    "rotation", DisplaySettings.DEFAULT, Space.WORLD,
                    1, 1,
                    MathEngine.compile("0", EquationDef.VARS),
                    MathEngine.compile("0", EquationDef.VARS),
                    MathEngine.compile("0", EquationDef.VARS),
                    MathEngine.compile("1", EquationDef.VARS),
                    MathEngine.compile("1", EquationDef.VARS),
                    MathEngine.compile("1", EquationDef.VARS),
                    MathEngine.compile("0", EquationDef.VARS),
                    MathEngine.compile("90", EquationDef.VARS),   // rotY = 90°
                    MathEngine.compile("0", EquationDef.VARS),
                    MathEngine.compile("127", EquationDef.VARS)
            );
            BakedSequence seq = def.bake(0.0);
            TransformSnapshot snap = seq.frames()[0].snapshot();

            // 左旋转应为单位四元数
            assertEquals(0f, snap.lrx(), 1e-5f);
            assertEquals(0f, snap.lry(), 1e-5f);
            assertEquals(0f, snap.lrz(), 1e-5f);
            assertEquals(1f, snap.lrw(), 1e-5f);

            // 右旋转应为绕 Y 轴 90° 的四元数
            // 绕 Y 轴 90°: (0, sin(45°), 0, cos(45°)) = (0, 0.7071, 0, 0.7071)
            assertEquals(0f, snap.rrx(), 1e-4f);
            assertEquals(0.7071f, snap.rry(), 1e-3f);
            assertEquals(0f, snap.rrz(), 1e-4f);
            assertEquals(0.7071f, snap.rrw(), 1e-3f);
        }

        @Test
        @DisplayName("VARS 常量包含 t 和 r")
        void varsConstant() {
            assertArrayEquals(new String[]{"t", "r"}, EquationDef.VARS);
        }
    }

    // ── PresetDef ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("PresetDef")
    class PresetDefTests {

        @Test
        @DisplayName("type() 返回 PRESET")
        void type() {
            var keyframe = createSimpleKeyframe();
            var preset = new PresetDef("my_preset", DisplaySettings.DEFAULT, keyframe);
            assertEquals(AnimationType.PRESET, preset.type());
        }

        @Test
        @DisplayName("totalDurationTicks() 委托给 resolved")
        void totalDuration() {
            var keyframe = createSimpleKeyframe();
            var preset = new PresetDef("preset", DisplaySettings.DEFAULT, keyframe);
            assertEquals(keyframe.totalDurationTicks(), preset.totalDurationTicks());
        }

        @Test
        @DisplayName("bake() 委托给 resolved 的 bake")
        void bakeDelegates() {
            var keyframe = createSimpleKeyframe();
            var preset = new PresetDef("preset", DisplaySettings.DEFAULT, keyframe);
            BakedSequence fromPreset = preset.bake(0.5);
            BakedSequence fromKeyframe = keyframe.bake(0.5);
            assertSame(fromPreset, fromKeyframe, "preset 应委托 bake 给 resolved");
        }

        @Test
        @DisplayName("settings 可以与 resolved 不同")
        void settingsCanDiffer() {
            var keyframe = createSimpleKeyframe();
            var customSettings = new DisplaySettings(
                    cn.warriorview.animation.api.Billboard.FIXED,
                    true, true, 0, 2.0f, 5, 100, 1.0f, 0.5f, 0, 400, OffsetExpr.ZERO);
            var preset = new PresetDef("preset", customSettings, keyframe);
            assertNotEquals(keyframe.settings(), preset.settings());
            assertEquals(customSettings, preset.settings());
        }

        @Test
        @DisplayName("resolved() 返回被包装的定义")
        void resolvedReturnsWrapped() {
            var keyframe = createSimpleKeyframe();
            var preset = new PresetDef("preset", DisplaySettings.DEFAULT, keyframe);
            assertSame(keyframe, preset.resolved());
        }

        private KeyframeDef createSimpleKeyframe() {
            var frame = new BakedFrame(0, 0, 0, TransformSnapshot.IDENTITY);
            var seq = new BakedSequence(new BakedFrame[]{frame}, 10, DisplaySettings.DEFAULT);
            return new KeyframeDef("base", DisplaySettings.DEFAULT, Space.WORLD, seq);
        }
    }

    // ── AnimationDef 接口契约 ────────────────────────────────────────────────

    @Test
    @DisplayName("AnimationDef 密封仅允许三个实现")
    void sealedPermits() {
        // 通过 instanceof 验证密封层次
        AnimationDef keyframe = new KeyframeDef("k",
                DisplaySettings.DEFAULT, Space.WORLD,
                new BakedSequence(new BakedFrame[0], 0, DisplaySettings.DEFAULT));

        assertTrue(keyframe instanceof KeyframeDef);
        assertFalse(keyframe instanceof EquationDef);
        assertFalse(keyframe instanceof PresetDef);
    }
}
