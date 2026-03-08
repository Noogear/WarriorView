package cn.warriorview.animation.parse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试 SyntaxSugarResolver 的公共数学工具方法：
 * eulerToQuaternion 和四元数性质验证。
 */
@DisplayName("SyntaxSugarResolver 数学工具测试")
class TestSyntaxSugarMath {

    private static final float EPSILON = 1e-4f;

    // ── eulerToQuaternion ────────────────────────────────────────────────────

    @Nested
    @DisplayName("eulerToQuaternion")
    class EulerToQuaternionTests {

        @Test
        @DisplayName("零角度 → 单位四元数")
        void zeroAnglesProduceIdentity() {
            float[] q = SyntaxSugarResolver.eulerToQuaternion(0, 0, 0);
            assertQuaternionEquals(0, 0, 0, 1, q);
        }

        @Test
        @DisplayName("绕 X 轴 90° 旋转")
        void rotateX90() {
            float[] q = SyntaxSugarResolver.eulerToQuaternion(90, 0, 0);
            // 绕 X 轴 90°: (sin(45°), 0, 0, cos(45°))
            float s = (float) Math.sin(Math.toRadians(45));
            float c = (float) Math.cos(Math.toRadians(45));
            assertQuaternionEquals(s, 0, 0, c, q);
        }

        @Test
        @DisplayName("绕 Y 轴 90° 旋转")
        void rotateY90() {
            float[] q = SyntaxSugarResolver.eulerToQuaternion(0, 90, 0);
            float s = (float) Math.sin(Math.toRadians(45));
            float c = (float) Math.cos(Math.toRadians(45));
            assertQuaternionEquals(0, s, 0, c, q);
        }

        @Test
        @DisplayName("绕 Z 轴 90° 旋转")
        void rotateZ90() {
            float[] q = SyntaxSugarResolver.eulerToQuaternion(0, 0, 90);
            float s = (float) Math.sin(Math.toRadians(45));
            float c = (float) Math.cos(Math.toRadians(45));
            assertQuaternionEquals(0, 0, s, c, q);
        }

        @Test
        @DisplayName("180° 旋转")
        void rotate180() {
            float[] q = SyntaxSugarResolver.eulerToQuaternion(0, 180, 0);
            // 绕 Y 轴 180°: (0, 1, 0, 0)
            assertEquals(0f, q[0], EPSILON);
            assertEquals(1f, Math.abs(q[1]), EPSILON); // y = ±1
            assertEquals(0f, q[2], EPSILON);
            assertEquals(0f, q[3], EPSILON); // w ≈ 0
        }

        @Test
        @DisplayName("360° 旋转 ≈ 单位四元数（符号可能取反）")
        void rotate360() {
            float[] q = SyntaxSugarResolver.eulerToQuaternion(0, 360, 0);
            // 360° 旋转等于 (0,0,0,-1) 或 (0,0,0,1)，两者表示同一旋转
            assertEquals(1.0f, Math.abs(q[3]), EPSILON);
        }

        @Test
        @DisplayName("负角度与正角度互补")
        void negativeAngle() {
            float[] qPos = SyntaxSugarResolver.eulerToQuaternion(0, 45, 0);
            float[] qNeg = SyntaxSugarResolver.eulerToQuaternion(0, -45, 0);
            // Y 分量应符号相反
            assertEquals(qPos[1], -qNeg[1], EPSILON);
            // W 分量应相同
            assertEquals(qPos[3], qNeg[3], EPSILON);
        }

        @Test
        @DisplayName("输出四元数是单位四元数（模长 = 1）")
        void unitQuaternion() {
            float[][] testAngles = {
                    {0, 0, 0}, {90, 0, 0}, {0, 90, 0}, {0, 0, 90},
                    {45, 30, 60}, {-90, 180, 45}, {15, -30, 270}
            };
            for (float[] angles : testAngles) {
                float[] q = SyntaxSugarResolver.eulerToQuaternion(angles[0], angles[1], angles[2]);
                float norm = q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3];
                assertEquals(1.0f, norm, EPSILON,
                        "四元数模长应为 1，角度: " + angles[0] + "," + angles[1] + "," + angles[2]);
            }
        }

        @Test
        @DisplayName("小角度旋转近似正确")
        void smallAngle() {
            float[] q = SyntaxSugarResolver.eulerToQuaternion(1, 0, 0);
            // 小角度时 sin(θ/2) ≈ θ/2, cos(θ/2) ≈ 1
            assertTrue(q[0] > 0, "x 分量应为正");
            assertTrue(q[3] > 0.999f, "w 分量应接近 1");
        }

        @Test
        @DisplayName("组合旋转的模长仍为 1")
        void combinedRotationUnit() {
            float[] q = SyntaxSugarResolver.eulerToQuaternion(30, 45, 60);
            float norm = q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3];
            assertEquals(1.0f, norm, EPSILON);
        }
    }

    // ── 辅助 ─────────────────────────────────────────────────────────────────

    private static void assertQuaternionEquals(float x, float y, float z, float w, float[] q) {
        assertEquals(x, q[0], EPSILON, "qx");
        assertEquals(y, q[1], EPSILON, "qy");
        assertEquals(z, q[2], EPSILON, "qz");
        assertEquals(w, q[3], EPSILON, "qw");
    }
}
