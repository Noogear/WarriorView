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

    // 标准测试参数：攻击者在 (0,1.62,0)，被害者在 (3,0,0)，宽高 0.6×1.8
    static final double EYE_X = 0, EYE_Y = 1.62, EYE_Z = 0;
    static final double V_X = 3.0, V_Y = 0.0, V_Z = 0.0;
    static final double V_H = 1.8, V_W = 0.6;

    // ── ATTACKER ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("ATTACKER 策略")
    class AttackerTests {

        @Test
        @DisplayName("返回攻击者眼睛位置")
        void returnsAttackerEye() {
            Vector3d pos = IndicatorSpawnStrategy.ATTACKER.resolve(
                    EYE_X, EYE_Y, EYE_Z, 0, -90, V_X, V_Y, V_Z, V_H, V_W);
            assertEquals(EYE_X, pos.getX(), 1e-9);
            assertEquals(EYE_Y, pos.getY(), 1e-9);
            assertEquals(EYE_Z, pos.getZ(), 1e-9);
        }

        @Test
        @DisplayName("忽略被害者参数")
        void ignoresVictimParams() {
            Vector3d pos = IndicatorSpawnStrategy.ATTACKER.resolve(
                    5, 10, -3, 45, 90, 100, 200, 300, 50, 20);
            assertEquals(5.0, pos.getX(), 1e-9);
            assertEquals(10.0, pos.getY(), 1e-9);
            assertEquals(-3.0, pos.getZ(), 1e-9);
        }
    }

    // ── PROJECTED ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("PROJECTED 策略")
    class ProjectedTests {

        @Test
        @DisplayName("正面瞄准时投影点在被害者方向上")
        void forwardProjection() {
            // yaw=-90 表示面朝正 X 方向（MC 坐标系），攻击者在原点注视 (3,0,0) 的被害者
            Vector3d pos = IndicatorSpawnStrategy.PROJECTED.resolve(
                    EYE_X, EYE_Y, EYE_Z, 0, -90, V_X, V_Y, V_Z, V_H, V_W);
            // 投影点应在攻击者和被害者之间（X 方向）
            assertNotNull(pos);
            assertTrue(pos.getX() > EYE_X, "投影点应在被害者方向上");
        }

        @Test
        @DisplayName("投影结果不为 null")
        void neverNull() {
            Vector3d pos = IndicatorSpawnStrategy.PROJECTED.resolve(
                    0, 0, 0, 0, 0, 5, 0, 5, 2.0, 0.6);
            assertNotNull(pos);
        }
    }

    // ── CLAMPED ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("CLAMPED 策略")
    class ClampedTests {

        @Test
        @DisplayName("攻击者在被害者内部时返回攻击者位置")
        void eyeInsideAABB() {
            // 攻击者眼睛恰好在被害者 AABB+padding 内部
            Vector3d pos = IndicatorSpawnStrategy.CLAMPED.resolve(
                    V_X, V_Y + V_H / 2, V_Z, 0, 0, V_X, V_Y, V_Z, V_H, V_W);
            // 几何钳制：攻击者已在 AABB 内部，结果应很接近攻击者位置
            assertEquals(V_X, pos.getX(), 0.5);
        }

        @Test
        @DisplayName("攻击者远离时被钳制到 AABB 边界")
        void eyeOutsideAABB() {
            Vector3d pos = IndicatorSpawnStrategy.CLAMPED.resolve(
                    100, 100, 100, 0, 0, V_X, V_Y, V_Z, V_H, V_W);
            // 结果应被钳制到 AABB 边界附近，不超过 AABB + padding
            double hw = V_W / 2.0 + 0.3;
            assertTrue(pos.getX() <= V_X + hw + 0.01, "X 被钳制");
            assertTrue(pos.getY() <= V_Y + V_H + 0.3 + 0.01, "Y 被钳制");
        }

        @Test
        @DisplayName("各维度独立钳制")
        void independentClamping() {
            // X 在范围内，Y 在范围外
            Vector3d pos = IndicatorSpawnStrategy.CLAMPED.resolve(
                    V_X, 100, V_Z, 0, 0, V_X, V_Y, V_Z, V_H, V_W);
            assertEquals(V_X, pos.getX(), 0.5);
            assertTrue(pos.getY() <= V_Y + V_H + 0.3 + 0.01);
        }
    }

    // ── RAY_TRACED ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("RAY_TRACED 策略")
    class RayTracedTests {

        @Test
        @DisplayName("射线命中 AABB 返回交点")
        void hitReturnsIntersection() {
            // 面朝正 X 方向 (yaw=-90)，被害者在 (3,0,0)
            Vector3d pos = IndicatorSpawnStrategy.RAY_TRACED.resolve(
                    0, 0.9, 0, 0, -90, V_X, V_Y, V_Z, V_H, V_W);
            assertNotNull(pos);
            // 交点应在 AABB 附近
            assertTrue(pos.getX() > 0, "交点应在正 X 方向");
            assertTrue(pos.getX() < V_X + 1, "交点应在 AABB 附近");
        }

        @Test
        @DisplayName("射线未命中时返回保底位置")
        void missReturnsFallback() {
            // 面朝完全相反方向 (yaw=90)，射线不会命中
            Vector3d pos = IndicatorSpawnStrategy.RAY_TRACED.resolve(
                    0, 0.9, 0, 0, 90, V_X, V_Y, V_Z, V_H, V_W);
            // 保底位置：(vX, vY + vH*0.75, vZ)
            assertEquals(V_X, pos.getX(), 1e-9);
            assertEquals(V_Y + V_H * 0.75, pos.getY(), 1e-9);
            assertEquals(V_Z, pos.getZ(), 1e-9);
        }

        @Test
        @DisplayName("射线距离超过 5 格时返回保底位置")
        void tooFarReturnsFallback() {
            // 被害者太远
            Vector3d pos = IndicatorSpawnStrategy.RAY_TRACED.resolve(
                    0, 0.9, 0, 0, -90, 100, 0, 0, V_H, V_W);
            // 保底位置
            assertEquals(100.0, pos.getX(), 1e-9);
        }
    }

    // ── 跨策略通用测试 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("所有策略均返回非空 Vector3d")
    void allStrategiesReturnNonNull() {
        for (IndicatorSpawnStrategy strategy : IndicatorSpawnStrategy.values()) {
            Vector3d pos = strategy.resolve(
                    EYE_X, EYE_Y, EYE_Z, 0, -90, V_X, V_Y, V_Z, V_H, V_W);
            assertNotNull(pos, strategy.name() + " should not return null");
        }
    }

    @Test
    @DisplayName("策略枚举包含四种策略")
    void fourStrategies() {
        assertEquals(4, IndicatorSpawnStrategy.values().length);
    }
}
