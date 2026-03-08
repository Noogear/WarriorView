package cn.warriorview.animation.data;

import gloomlib.math.api.MathEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试 OffsetExpr —— 每实例随机偏移表达式。
 * 验证 ZERO 常量、evaluate、evaluateInto 以及自定义表达式行为。
 */
@DisplayName("OffsetExpr 偏移表达式测试")
class TestOffsetExpr {

    @Test
    @DisplayName("ZERO 常量始终返回 (0,0,0)")
    void zeroConstantReturnsOrigin() {
        double[] result = OffsetExpr.ZERO.evaluate(0.0);
        assertEquals(0.0, result[0]);
        assertEquals(0.0, result[1]);
        assertEquals(0.0, result[2]);
    }

    @Test
    @DisplayName("ZERO 常量对任意 r 值均返回 (0,0,0)")
    void zeroConstantAnyRandom() {
        for (double r : new double[]{0.0, 0.25, 0.5, 0.75, 0.999}) {
            double[] result = OffsetExpr.ZERO.evaluate(r);
            assertEquals(0.0, result[0], "r=" + r);
            assertEquals(0.0, result[1], "r=" + r);
            assertEquals(0.0, result[2], "r=" + r);
        }
    }

    @Test
    @DisplayName("ZERO evaluateInto 写入预分配数组")
    void zeroEvaluateInto() {
        float[] out = new float[3];
        OffsetExpr.ZERO.evaluateInto(0.5, out);
        assertEquals(0f, out[0]);
        assertEquals(0f, out[1]);
        assertEquals(0f, out[2]);
    }

    @Test
    @DisplayName("常量表达式返回固定偏移")
    void constantExpressions() {
        OffsetExpr expr = new OffsetExpr(
                MathEngine.compile("0.5", OffsetExpr.VARS),
                MathEngine.compile("1.0", OffsetExpr.VARS),
                MathEngine.compile("-0.3", OffsetExpr.VARS)
        );
        double[] result = expr.evaluate(0.0);
        assertEquals(0.5, result[0], 1e-9);
        assertEquals(1.0, result[1], 1e-9);
        assertEquals(-0.3, result[2], 1e-9);
    }

    @Test
    @DisplayName("变量 r 参与计算")
    void variableRUsed() {
        // x = r * 2, y = r, z = r * 0.5
        OffsetExpr expr = new OffsetExpr(
                MathEngine.compile("r * 2", OffsetExpr.VARS),
                MathEngine.compile("r", OffsetExpr.VARS),
                MathEngine.compile("r * 0.5", OffsetExpr.VARS)
        );
        double[] result = expr.evaluate(0.4);
        assertEquals(0.8, result[0], 1e-9);
        assertEquals(0.4, result[1], 1e-9);
        assertEquals(0.2, result[2], 1e-9);
    }

    @Test
    @DisplayName("evaluateInto 结果与 evaluate 一致")
    void evaluateIntoMatchesEvaluate() {
        OffsetExpr expr = new OffsetExpr(
                MathEngine.compile("r * 0.6 - 0.3", OffsetExpr.VARS),
                MathEngine.compile("r * 0.5", OffsetExpr.VARS),
                MathEngine.compile("r * 0.6 - 0.3", OffsetExpr.VARS)
        );
        double r = 0.7;
        double[] dResult = expr.evaluate(r);
        float[] fResult = new float[3];
        expr.evaluateInto(r, fResult);

        assertEquals((float) dResult[0], fResult[0], 1e-6f);
        assertEquals((float) dResult[1], fResult[1], 1e-6f);
        assertEquals((float) dResult[2], fResult[2], 1e-6f);
    }

    @Test
    @DisplayName("边界值 r=0 和 r≈1")
    void boundaryRandomValues() {
        OffsetExpr expr = new OffsetExpr(
                MathEngine.compile("r", OffsetExpr.VARS),
                MathEngine.compile("r", OffsetExpr.VARS),
                MathEngine.compile("r", OffsetExpr.VARS)
        );
        // r = 0
        double[] r0 = expr.evaluate(0.0);
        assertEquals(0.0, r0[0], 1e-9);
        // r ≈ 1
        double[] r1 = expr.evaluate(0.9999);
        assertTrue(r1[0] > 0.99);
    }

    @Test
    @DisplayName("VARS 常量包含 'r'")
    void varsContainsR() {
        assertArrayEquals(new String[]{"r"}, OffsetExpr.VARS);
    }

    @Test
    @DisplayName("复杂数学表达式（sin/cos）")
    void complexMathExpression() {
        OffsetExpr expr = new OffsetExpr(
                MathEngine.compile("sin(r * 3.14159)", OffsetExpr.VARS),
                MathEngine.compile("cos(r * 3.14159)", OffsetExpr.VARS),
                MathEngine.compile("0", OffsetExpr.VARS)
        );
        double[] result = expr.evaluate(0.5);
        // sin(π/2) ≈ 1, cos(π/2) ≈ 0
        assertEquals(Math.sin(0.5 * 3.14159), result[0], 1e-4);
        assertEquals(Math.cos(0.5 * 3.14159), result[1], 1e-4);
        assertEquals(0.0, result[2], 1e-9);
    }
}
