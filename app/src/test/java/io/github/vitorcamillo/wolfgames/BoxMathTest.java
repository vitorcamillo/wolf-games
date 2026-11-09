package io.github.vitorcamillo.wolfgames;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BoxMathTest {

    private static final float EPS = 1e-4f;

    @Test
    public void slotAtZeroIsCentered() {
        float[] out = new float[3];
        BoxMath.slot(0f, out);
        assertEquals(0f, out[0], EPS);
        assertEquals(-22f, out[1], EPS);
        assertEquals(-1.05f, out[2], EPS);
    }

    @Test
    public void slotAtOneIsSideSlot() {
        float[] out = new float[3];
        BoxMath.slot(1f, out);
        assertEquals(1.22f, out[0], EPS);
        assertEquals(60f, out[1], EPS);
        assertEquals(0.7208f, out[2], EPS);
    }

    @Test
    public void slotAtMinusOneIsMirroredSideSlot() {
        float[] out = new float[3];
        BoxMath.slot(-1f, out);
        assertEquals(-1.22f, out[0], EPS);
        assertEquals(-60f, out[1], EPS);
        assertEquals(0.7208f, out[2], EPS);
    }

    @Test
    public void slotFarExtendsLinearly() {
        float[] out = new float[3];
        BoxMath.slot(3f, out);
        assertEquals(1.22f + 2f * 0.283f, out[0], EPS);
        assertEquals(60f, out[1], EPS);
        assertEquals(0.70f + 3f * 0.0208f, out[2], EPS);
    }

    @Test
    public void slotHalfwayHasInterpolatedRotation() {
        float[] out = new float[3];
        BoxMath.slot(0.5f, out);
        assertEquals(19f, out[1], EPS);
    }

    @Test
    public void slotIsContinuousNearZeroAndOne() {
        float[] a = new float[3];
        float[] b = new float[3];
        // em 0: x e z contínuos (rotY tende a CENTER_ANGLE dos dois lados)
        BoxMath.slot(0f, a);
        BoxMath.slot(1e-4f, b);
        assertEquals(a[0], b[0], 1e-3f);
        assertEquals(a[2], b[2], 1e-3f);
        BoxMath.slot(-1e-4f, b);
        assertEquals(a[0], b[0], 1e-3f);
        assertEquals(a[2], b[2], 1e-3f);

        // em 1: os três componentes contínuos dos dois lados
        BoxMath.slot(1f, a);
        BoxMath.slot(1f - 1e-4f, b);
        for (int i = 0; i < 3; i++) assertEquals(a[i], b[i], i == 1 ? 1e-2f : 1e-3f);
        BoxMath.slot(1f + 1e-4f, b);
        for (int i = 0; i < 3; i++) assertEquals(a[i], b[i], i == 1 ? 1e-2f : 1e-3f);
    }

    @Test
    public void slotMirrorsXAndZButNotRotation() {
        float[] pos = new float[3];
        float[] neg = new float[3];
        BoxMath.slot(0.5f, pos);
        BoxMath.slot(-0.5f, neg);
        assertEquals(-pos[0], neg[0], EPS);
        assertEquals(pos[2], neg[2], EPS);
        assertFalse(pos[1] == neg[1]);
        assertTrue(pos[1] > BoxMath.CENTER_ANGLE && neg[1] < BoxMath.CENTER_ANGLE);
    }

    @Test
    public void projectWithoutRotationAtZeroDepthIsCenterPlusPoint() {
        float[] pts = {3f, -4f, 0f, -10f, 7f, 0f};
        float[] out = new float[4];
        float meanZ = BoxMath.project(pts, 2, 0f, 0f, 0f, 0f, 0f, 500f, 100f, 200f, out);
        assertEquals(0f, meanZ, EPS);
        assertArrayEquals(new float[]{103f, 196f, 90f, 207f}, out, EPS);
    }

    @Test
    public void projectReturnsMeanTranslatedDepth() {
        float[] pts = {0f, 0f, 0f, 0f, 0f, 0f};
        float[] out = new float[4];
        float meanZ = BoxMath.project(pts, 2, 0f, 0f, 0f, 0f, 25f, 1000f, 0f, 0f, out);
        assertEquals(25f, meanZ, EPS);
    }

    @Test
    public void frontFacesCameraAndBackDoesNot() {
        float[] corners = new float[12];
        float[] out = new float[8];

        BoxMath.faceCorners(BoxMath.FRONT, 1f, 1f, 1f, corners);
        BoxMath.project(corners, 4, 0f, 0f, 0f, 0f, 0f, 1000f, 0f, 0f, out);
        assertTrue(BoxMath.facing(out));

        BoxMath.faceCorners(BoxMath.BACK, 1f, 1f, 1f, corners);
        BoxMath.project(corners, 4, 0f, 0f, 0f, 0f, 0f, 1000f, 0f, 0f, out);
        assertFalse(BoxMath.facing(out));
    }

    @Test
    public void spineFacesCameraAtSideAngle() {
        float[] corners = new float[12];
        float[] out = new float[8];
        // caixa à esquerda (offset negativo) mostra a lombada com rotação -60
        BoxMath.faceCorners(BoxMath.SPINE, 1f, 1f, 1f, corners);
        BoxMath.project(corners, 4, -60f, 0f, 0f, 0f, 0f, 1000f, 0f, 0f, out);
        assertTrue(BoxMath.facing(out));
    }

    @Test
    public void facingRejectsReversedQuad() {
        float[] ccw = {0f, 0f, 0f, 1f, 1f, 1f, 1f, 0f};
        assertFalse(BoxMath.facing(ccw));
        float[] cw = {0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f};
        assertTrue(BoxMath.facing(cw));
    }

    @Test
    public void rotateYAroundOriginBy90Degrees() {
        float[] pts = {1f, 2f, 0f};
        BoxMath.rotateYAround(pts, 1, 0f, 0f, 90f);
        assertEquals(0f, pts[0], EPS);
        assertEquals(2f, pts[1], EPS);
        assertEquals(-1f, pts[2], EPS);
    }

    @Test
    public void rotateYAroundPivotKeepsPivotFixed() {
        float[] pts = {5f, 0f, 3f};
        BoxMath.rotateYAround(pts, 1, 5f, 3f, 37f);
        assertEquals(5f, pts[0], EPS);
        assertEquals(3f, pts[2], EPS);
    }

    @Test
    public void easeOutBoundsAndClamp() {
        assertEquals(0f, BoxMath.easeOut(0f), EPS);
        assertEquals(1f, BoxMath.easeOut(1f), EPS);
        assertEquals(0f, BoxMath.easeOut(-5f), EPS);
        assertEquals(1f, BoxMath.easeOut(5f), EPS);
        assertEquals(0.875f, BoxMath.easeOut(0.5f), EPS);
    }

    @Test
    public void clampLimitsValues() {
        assertEquals(0, BoxMath.clamp(-3, 0, 9));
        assertEquals(9, BoxMath.clamp(12, 0, 9));
        assertEquals(4, BoxMath.clamp(4, 0, 9));
    }

    @Test
    public void visibleRangeAtEdgesAndCenter() {
        assertArrayEquals(new int[]{0, 9}, BoxMath.visibleRange(0f, 20));
        assertArrayEquals(new int[]{1, 19}, BoxMath.visibleRange(10f, 20));
        assertArrayEquals(new int[]{10, 19}, BoxMath.visibleRange(19f, 20));
    }

    @Test
    public void visibleRangeWithZeroCountIsEmpty() {
        assertArrayEquals(new int[]{0, -1}, BoxMath.visibleRange(3f, 0));
    }
}
