package cn.warriorview.animation.api;

import com.github.retrooper.packetevents.util.Vector3d;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 全面测试 IndicatorSpawnStrategy 的四种生成位置算法。
 * 覆盖正常情况、边界条件和几何正确性。
 */
@DisplayName("IndicatorSpawnStrategy 生成位置策略测试")
class TestIndicatorSpawnStrategy {

    // 标准测试参数：来源足部 (0,0,0) 眼睛 Y=1.62，目标足部 (3,0,0)，宽高 0.6×1.8
    static final double S_X = 0, S_Y = 0, S_Z = 0;
    static final double S_EYE_Y = 1.62;
    static final double T_X = 3.0, T_Y = 0.0, T_Z = 0.0;
    static final double T_H = 1.8, T_W = 0.6;
    static final double T_EYE_Y = T_Y + 1.55;

    // ── SOURCE_ORIGIN ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("SOURCE_ORIGIN 策略")
    class SourceOriginTests {

        @Test
        @DisplayName("返回来源眼睛位置")
        void returnsSourceEye() {
            Vector3d pos = IndicatorSpawnStrategy.SOURCE_ORIGIN.resolve(
                    S_X, S_Y, S_Z, S_EYE_Y, T_EYE_Y, 0, -90, T_X, T_Y, T_Z, T_H, T_W);
            assertEquals(S_X, pos.getX(), 1e-9);
            assertEquals(S_EYE_Y, pos.getY(), 1e-9);
            assertEquals(S_Z, pos.getZ(), 1e-9);
        }

        @Test
        @DisplayName("忽略目标参数")
        void ignoresTargetParams() {
            Vector3d pos = IndicatorSpawnStrategy.SOURCE_ORIGIN.resolve(
                    5, 0, -3, 10, 0, 45, 90, 100, 200, 300, 50, 20);
            assertEquals(5.0, pos.getX(), 1e-9);
            assertEquals(10.0, pos.getY(), 1e-9);  // sEyeY
            assertEquals(-3.0, pos.getZ(), 1e-9);
        }
    }

    // ── AIM ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("AIM 策略")
    class AimTests {

        @Test
        @DisplayName("正面瞄准时投影点在目标方向上")
        void forwardProjection() {
            // yaw=-90 表示面朝正 X 方向（MC 坐标系），来源在原点注視 (3,0,0) 的目标
            Vector3d pos = IndicatorSpawnStrategy.AIM.resolve(
                    S_X, S_Y, S_Z, S_EYE_Y, T_EYE_Y, 0, -90, T_X, T_Y, T_Z, T_H, T_W);
            // 投影点应在来源和目标之间（X 方向）
            assertNotNull(pos);
            assertTrue(pos.getX() > S_X, "投影点应在目标方向上");
        }

        @Test
        @DisplayName("投影结果不为 null")
        void neverNull() {
            Vector3d pos = IndicatorSpawnStrategy.AIM.resolve(
                    0, 0, 0, 0, 0, 0, 0, 5, 0, 5, 2.0, 0.6);
            assertNotNull(pos);
        }
    }

    // ── SURFACE ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("SURFACE 策略")
    class SurfaceTests {

        @Test
        @DisplayName("来源在目标内部时返回来源位置")
        void eyeInsideAABB() {
            // 来源眼睛恰好在目标 AABB+padding 内部
            Vector3d pos = IndicatorSpawnStrategy.SURFACE.resolve(
                    T_X, 0, T_Z, T_Y + T_H / 2, T_EYE_Y, 0, 0, T_X, T_Y, T_Z, T_H, T_W);
            // 几何钳制：来源已在 AABB 内部，结果应很接近来源位置
            assertEquals(T_X, pos.getX(), 0.5);
        }

        @Test
        @DisplayName("来源远离时被钳制到 AABB 边界")
        void eyeOutsideAABB() {
            Vector3d pos = IndicatorSpawnStrategy.SURFACE.resolve(
                    100, 0, 100, 100, T_EYE_Y, 0, 0, T_X, T_Y, T_Z, T_H, T_W);
            // 结果应被钳制到 AABB 边界附近，不超过 AABB + padding
            double hw = T_W / 2.0 + 0.3;
            assertTrue(pos.getX() <= T_X + hw + 0.01, "X 被钳制");
            assertTrue(pos.getY() <= T_Y + T_H + 0.3 + 0.01, "Y 被钳制");
        }

        @Test
        @DisplayName("各维度独立钳制")
        void independentClamping() {
            // X 在范围内，Y 在范围外
            Vector3d pos = IndicatorSpawnStrategy.SURFACE.resolve(
                    T_X, 0, T_Z, 100, T_EYE_Y, 0, 0, T_X, T_Y, T_Z, T_H, T_W);
            assertEquals(T_X, pos.getX(), 0.5);
            assertTrue(pos.getY() <= T_Y + T_H + 0.3 + 0.01);
        }
    }

    // ── IMPACT ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("IMPACT 策略")
    class ImpactTests {

        @Test
        @DisplayName("射线命中 AABB 返回交点")
        void hitReturnsIntersection() {
            // 面朝正 X 方向 (yaw=-90)，被害者在 (3,0,0)
            Vector3d pos = IndicatorSpawnStrategy.IMPACT.resolve(
                    0, 0, 0, 0.9, T_EYE_Y, 0, -90, T_X, T_Y, T_Z, T_H, T_W);
            assertNotNull(pos);
            // 交点应在 AABB 附近
            assertTrue(pos.getX() > 0, "交点应在正 X 方向");
            assertTrue(pos.getX() < T_X + 1, "交点应在 AABB 附近");
        }

        @Test
        @DisplayName("射线未命中时返回保底位置")
        void missReturnsFallback() {
            // 面朝完全相反方向 (yaw=90)，射线不会命中
            Vector3d pos = IndicatorSpawnStrategy.IMPACT.resolve(
                    0, 0, 0, 0.9, T_EYE_Y, 0, 90, T_X, T_Y, T_Z, T_H, T_W);
            // 保底位置：(tX, tY + tH*0.75, tZ)
            assertEquals(T_X, pos.getX(), 1e-9);
            assertEquals(T_Y + T_H * 0.75, pos.getY(), 1e-9);
            assertEquals(T_Z, pos.getZ(), 1e-9);
        }

        @Test
        @DisplayName("射线距离超过 5 格时返回保底位置")
        void tooFarReturnsFallback() {
            // 目标太远
            Vector3d pos = IndicatorSpawnStrategy.IMPACT.resolve(
                    0, 0, 0, 0.9, T_EYE_Y, 0, -90, 100, 0, 0, T_H, T_W);
            // 保底位置
            assertEquals(100.0, pos.getX(), 1e-9);
        }
    }

    // ── VICTIM_EYE ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("TARGET_EYE 策略")
    class TargetEyeTests {

        @Test
        @DisplayName("返回目标眼睛位置")
        void returnsTargetEye() {
            Vector3d pos = IndicatorSpawnStrategy.TARGET_EYE.resolve(
                    S_X, S_Y, S_Z, S_EYE_Y, T_EYE_Y, 0, -90, T_X, T_Y, T_Z, T_H, T_W);
            assertEquals(T_X, pos.getX(), 1e-9);
            assertEquals(T_EYE_Y, pos.getY(), 1e-9);
            assertEquals(T_Z, pos.getZ(), 1e-9);
        }

        @Test
        @DisplayName("忽略来源参数")
        void ignoresSourceParams() {
            Vector3d pos = IndicatorSpawnStrategy.TARGET_EYE.resolve(
                    999, 999, 999, 999, T_EYE_Y, 45, 90, T_X, T_Y, T_Z, T_H, T_W);
            assertEquals(T_X, pos.getX(), 1e-9);
            assertEquals(T_EYE_Y, pos.getY(), 1e-9);
            assertEquals(T_Z, pos.getZ(), 1e-9);
        }
    }

    // ── VICTIM_BOTTOM ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("TARGET_BOTTOM 策略")
    class TargetBottomTests {

        @Test
        @DisplayName("返回目标脚底位置")
        void returnsTargetBottom() {
            Vector3d pos = IndicatorSpawnStrategy.TARGET_BOTTOM.resolve(
                    S_X, S_Y, S_Z, S_EYE_Y, T_EYE_Y, 0, -90, T_X, T_Y, T_Z, T_H, T_W);
            assertEquals(T_X, pos.getX(), 1e-9);
            assertEquals(T_Y, pos.getY(), 1e-9);
            assertEquals(T_Z, pos.getZ(), 1e-9);
        }

        @Test
        @DisplayName("忽略来源参数")
        void ignoresSourceParams() {
            Vector3d pos = IndicatorSpawnStrategy.TARGET_BOTTOM.resolve(
                    999, 999, 999, 999, 999, 45, 90, T_X, T_Y, T_Z, T_H, T_W);
            assertEquals(T_X, pos.getX(), 1e-9);
            assertEquals(T_Y, pos.getY(), 1e-9);
            assertEquals(T_Z, pos.getZ(), 1e-9);
        }
    }

    // ── 跨策略通用测试 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("所有策略均返回非空 Vector3d")
    void allStrategiesReturnNonNull() {
        for (IndicatorSpawnStrategy strategy : IndicatorSpawnStrategy.values()) {
            Vector3d pos = strategy.resolve(
                    S_X, S_Y, S_Z, S_EYE_Y, T_EYE_Y, 0, -90, T_X, T_Y, T_Z, T_H, T_W);
            assertNotNull(pos, strategy.name() + " should not return null");
        }
    }

    @Test
    @DisplayName("策略枚举包含六种策略")
    void sixStrategies() {
        assertEquals(6, IndicatorSpawnStrategy.values().length);
    }
}
