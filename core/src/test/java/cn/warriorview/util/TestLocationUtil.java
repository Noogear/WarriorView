package cn.warriorview.util;

import cn.warriorview.animation.api.IndicatorSpawnStrategy;
import com.github.retrooper.packetevents.util.Vector3d;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 全面测试 IndicatorSpawnStrategy 三种定位算法（AIM / SURFACE / IMPACT）的底层行为。
 * 覆盖正常几何、边界条件和退化情况。
 */
@DisplayName("IndicatorSpawnStrategy 算法测试")
class TestLocationUtil {

    // 标准目标：在 (5, 0, 0)，宽 0.6，高 1.8
    static final double V_X = 5.0, V_Y = 0.0, V_Z = 0.0;
    static final double V_H = 1.8, V_W = 0.6;

    // ── AIM（点积投影法） ────────────────────────────────────────────────────

    @Nested
    @DisplayName("AIM（点积投影法）")
    class ProjectedTests {

        @Test
        @DisplayName("正面瞄准：投影点在来源与目标之间")
        void frontAiming() {
            // 来源在原点，yaw=-90（面朝正 X）
            Vector3d pos = IndicatorSpawnStrategy.AIM.resolve(
                    0, 0.0, 0, 0.9, 0.0, 0f, -90f, V_X, V_Y, V_Z, V_H, V_W);
            assertTrue(pos.getX() > 0, "X 应为正（目标方向）");
            assertTrue(pos.getX() < V_X, "X 应小于目标中心");
        }

        @Test
        @DisplayName("防穿模：投影后拉回半宽 + 0.3")
        void pushbackPreventsClipping() {
            Vector3d pos = IndicatorSpawnStrategy.AIM.resolve(
                    0, 0.0, 0, 0.9, 0.0, 0f, -90f, 2, 0, 0, V_H, V_W);
            // 投影距离会被拉回 (vW/2 + 0.3) = 0.6
            assertTrue(pos.getX() < 2.0, "应被拉回到目标前方");
        }

        @Test
        @DisplayName("同一位置：目标和来源重叠")
        void samePosition() {
            Vector3d pos = IndicatorSpawnStrategy.AIM.resolve(
                    V_X, 0.0, V_Z, 0.9, 0.0, 0f, -90f, V_X, V_Y, V_Z, V_H, V_W);
            assertNotNull(pos);
            // 不应 NaN 或 Infinity
            assertFalse(Double.isNaN(pos.getX()));
            assertFalse(Double.isNaN(pos.getY()));
            assertFalse(Double.isNaN(pos.getZ()));
        }

        @Test
        @DisplayName("俯视瞄准：Y 方向投影偏低")
        void lookingDown() {
            // pitch=45（俯视）
            Vector3d pos = IndicatorSpawnStrategy.AIM.resolve(
                    0, 0.0, 0, 3, 0.0, 45f, -90f, V_X, V_Y, V_Z, V_H, V_W);
            assertTrue(pos.getY() < 3, "俯视时 Y 应低于眼高");
        }
    }

    // ── SURFACE（AABB 钳制法） ────────────────────────────────────────────────

    @Nested
    @DisplayName("SURFACE（AABB 钳制法）")
    class ClampedTests {

        @Test
        @DisplayName("来源在 AABB 内部时返回来源位置")
        void insideAABB() {
            Vector3d pos = IndicatorSpawnStrategy.SURFACE.resolve(
                    V_X, 0.0, V_Z, 0.9, 0.0, 0f, 0f, V_X, V_Y, V_Z, V_H, V_W);
            assertEquals(V_X, pos.getX(), 0.01);
            assertEquals(0.9, pos.getY(), 0.01);
            assertEquals(V_Z, pos.getZ(), 0.01);
        }

        @Test
        @DisplayName("来源在 X 远处时 X 被钳制到 AABB 边界")
        void outsideX() {
            Vector3d pos = IndicatorSpawnStrategy.SURFACE.resolve(
                    100, 0.0, 0, 0.9, 0.0, 0f, 0f, V_X, V_Y, V_Z, V_H, V_W);
            double maxX = V_X + V_W / 2.0 + 0.3;
            assertEquals(maxX, pos.getX(), 0.01);
        }

        @Test
        @DisplayName("来源在 Y 远处时 Y 被钳制")
        void outsideY() {
            Vector3d pos = IndicatorSpawnStrategy.SURFACE.resolve(
                    V_X, 0.0, V_Z, 100, 0.0, 0f, 0f, V_X, V_Y, V_Z, V_H, V_W);
            double maxY = V_Y + V_H + 0.3;
            assertEquals(maxY, pos.getY(), 0.01);
        }

        @Test
        @DisplayName("来源在负方向时被正确钳制")
        void negativeDirection() {
            Vector3d pos = IndicatorSpawnStrategy.SURFACE.resolve(
                    -100, 0.0, -100, -100, 0.0, 0f, 0f, V_X, V_Y, V_Z, V_H, V_W);
            double minX = V_X - V_W / 2.0 - 0.3;
            double minY = V_Y - 0.3;
            double minZ = V_Z - V_W / 2.0 - 0.3;
            assertEquals(minX, pos.getX(), 0.01);
            assertEquals(minY, pos.getY(), 0.01);
            assertEquals(minZ, pos.getZ(), 0.01);
        }

        @Test
        @DisplayName("无三角函数计算（性能验证：大量调用不超时）")
        void performanceNoTrig() {
            long start = System.nanoTime();
            for (int i = 0; i < 100_000; i++) {
                IndicatorSpawnStrategy.SURFACE.resolve(
                        i, 0.0, i, i, 0.0, 0f, 0f, V_X, V_Y, V_Z, V_H, V_W);
            }
            long elapsed = System.nanoTime() - start;
            // 100K 次调用应在合理时间内（< 100ms）
            assertTrue(elapsed < 100_000_000L, "AABB 钳制应极快");
        }
    }

    // ── IMPACT（Liang-Barsky 射线法） ─────────────────────────────────────────

    @Nested
    @DisplayName("IMPACT（Liang-Barsky 射线法）")
    class RayTracedTests {

        @Test
        @DisplayName("射线命中：交点在 AABB 表面")
        void hitReturnsIntersection() {
            // 来源在 (0,0.9,0), 面朝正 X (yaw=-90)
            Vector3d pos = IndicatorSpawnStrategy.IMPACT.resolve(
                    0, 0.0, 0, 0.9, 0.0, 0f, -90f, V_X, V_Y, V_Z, V_H, V_W);
            // 交点 X 应接近 AABB 左边界 (V_X - V_W/2 = 4.7)
            assertTrue(pos.getX() > 3 && pos.getX() < V_X + 1, "交点应在 AABB 附近");
        }

        @Test
        @DisplayName("射线未命中：返回保底位置")
        void missReturnsFallback() {
            // 面朝正 Z (yaw=0)，目标在正 X，不会命中
            Vector3d pos = IndicatorSpawnStrategy.IMPACT.resolve(
                    0, 0.0, 0, 0.9, 0.0, 0f, 0f, V_X, V_Y, V_Z, V_H, V_W);
            // 保底位置
            assertEquals(V_X, pos.getX(), 1e-9);
            assertEquals(V_Y + V_H * 0.75, pos.getY(), 1e-9);
            assertEquals(V_Z, pos.getZ(), 1e-9);
        }

        @Test
        @DisplayName("射线反向：未命中返回保底")
        void reverseRayMisses() {
            // yaw=90 → 面朝负 X 方向
            Vector3d pos = IndicatorSpawnStrategy.IMPACT.resolve(
                    0, 0.0, 0, 0.9, 0.0, 0f, 90f, V_X, V_Y, V_Z, V_H, V_W);
            assertEquals(V_X, pos.getX(), 1e-9);
        }

        @Test
        @DisplayName("超远目标：超过 5 格命中距离返回保底")
        void farTargetReturnsFallback() {
            Vector3d pos = IndicatorSpawnStrategy.IMPACT.resolve(
                    0, 0.0, 0, 0.9, 0.0, 0f, -90f, 50, 0, 0, V_H, V_W);
            assertEquals(50.0, pos.getX(), 1e-9);
        }

        @Test
        @DisplayName("近距离命中：距离 < 5 格")
        void closeHit() {
            Vector3d pos = IndicatorSpawnStrategy.IMPACT.resolve(
                    0, 0.0, 0, 0.9, 0.0, 0f, -90f, 2, 0, 0, V_H, V_W);
            assertTrue(pos.getX() > 0);
            assertTrue(pos.getX() < 3);
        }

        @Test
        @DisplayName("90° 俯视命中脚下目标")
        void lookingDownHit() {
            // 目标在正下方
            Vector3d pos = IndicatorSpawnStrategy.IMPACT.resolve(
                    5, 0.0, 0, 5, 0.0, 90f, 0f, 5, 0, 0, V_H, V_W);
            // 射线向下，应命中 AABB 顶面
            assertTrue(pos.getY() < 5, "交点应低于来源");
        }
    }

    // ── 跨算法比较 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("三种算法对同一输入均返回有限坐标")
    void allAlgorithmsReturnFiniteCoords() {
        // 每行：{sX, sEyeY, sZ, pitch, yaw, tX, tY, tZ, tH, tW}
        double[][] testCases = {
                {0, 1.62, 0, 0, -90, 5, 0, 0, 1.8, 0.6},
                {10, 5, 10, 30, 45, 12, 0, 12, 2.0, 0.8},
                {0, 0, 0, 0, 0, 3, 0, 3, 1.5, 0.4},
        };

        for (double[] tc : testCases) {
            Vector3d projected = IndicatorSpawnStrategy.AIM.resolve(
                    tc[0], 0.0, tc[2], tc[1], 0.0, (float) tc[3], (float) tc[4],
                    tc[5], tc[6], tc[7], tc[8], tc[9]);
            Vector3d clamped = IndicatorSpawnStrategy.SURFACE.resolve(
                    tc[0], 0.0, tc[2], tc[1], 0.0, 0f, 0f,
                    tc[5], tc[6], tc[7], tc[8], tc[9]);
            Vector3d rayTraced = IndicatorSpawnStrategy.IMPACT.resolve(
                    tc[0], 0.0, tc[2], tc[1], 0.0, (float) tc[3], (float) tc[4],
                    tc[5], tc[6], tc[7], tc[8], tc[9]);

            assertFinite(projected, "PROJECTED");
            assertFinite(clamped, "CLAMPED");
            assertFinite(rayTraced, "RAY_TRACED");
        }
    }

    private void assertFinite(Vector3d v, String label) {
        assertFalse(Double.isNaN(v.getX()), label + " X is NaN");
        assertFalse(Double.isNaN(v.getY()), label + " Y is NaN");
        assertFalse(Double.isNaN(v.getZ()), label + " Z is NaN");
        assertFalse(Double.isInfinite(v.getX()), label + " X is Inf");
        assertFalse(Double.isInfinite(v.getY()), label + " Y is Inf");
        assertFalse(Double.isInfinite(v.getZ()), label + " Z is Inf");
    }
}
