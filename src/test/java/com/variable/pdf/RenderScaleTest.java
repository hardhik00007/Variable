package com.variable.pdf;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenderScaleTest {
    @Test void multipliesZoomByOutputScaleAndClampsOutputScale() {
        assertEquals(2.0, RenderScale.effective(1.0, 2.0), 1e-9);
        assertEquals(1.0, RenderScale.effective(1.0, 0.5), 1e-9);
        assertEquals(4.0, RenderScale.effective(1.0, 10.0), 1e-9);
        assertEquals(1.0, RenderScale.effective(1.0, Double.NaN), 1e-9);
        assertEquals(RenderScale.MIN_SCALE, RenderScale.effective(0.01, 1.0), 1e-9);
    }

    @Test void quantizesUpwardOnlyAndOnlyChangesAtStepBoundaries() {
        assertEquals(1.0, RenderScale.quantize(1.0), 1e-9);
        assertEquals(1.05, RenderScale.quantize(1.01), 1e-9);
        assertEquals(2.4, RenderScale.quantize(1.2 * 2), 1e-9);
        for (double scale = 0.25; scale < 8; scale += 0.0137) {
            double quantized = RenderScale.quantize(scale);
            assertTrue(quantized >= scale - 1e-9, "never coarser than requested: " + scale);
            assertTrue(quantized < scale + RenderScale.STEP + 1e-9, "never wasteful: " + scale);
        }
        assertEquals(RenderScale.quantize(1.001), RenderScale.quantize(1.049), 1e-9);
    }

    @Test void leavesNormalPagesAloneAndClampsExtremeOnes() {
        assertEquals(2.0, RenderScale.clampToBudget(2.0, 595, 842), 1e-9);

        double clamped = RenderScale.clampToBudget(12.0, 595, 842);
        long pixels = (long) RenderScale.pixelSize(clamped, 595) * RenderScale.pixelSize(clamped, 842);
        assertTrue(pixels <= RenderScale.MAX_PIXELS * 1.01, "pixels " + pixels);
        assertTrue(clamped < 12.0);

        double poster = RenderScale.clampToBudget(3.0, 14_400, 14_400);
        long posterPixels = (long) RenderScale.pixelSize(poster, 14_400) * RenderScale.pixelSize(poster, 14_400);
        assertTrue(posterPixels <= RenderScale.MAX_PIXELS * 1.01, "pixels " + posterPixels);
        assertTrue(RenderScale.pixelSize(poster, 14_400) <= RenderScale.MAX_EDGE);
    }

    @Test void retinaMappingGivesOnePixelPerPhysicalPixel() {
        double logicalWidth = 900;           // page laid out 900 logical px wide
        double pageWidthPoints = 600;
        double zoom = logicalWidth / pageWidthPoints;
        double scale = RenderScale.effective(zoom, 2.0);
        int rasterWidth = RenderScale.pixelSize(scale, pageWidthPoints);
        assertEquals(logicalWidth * 2.0, rasterWidth, 1.0); // 1800 physical pixels for 900 logical
    }
}
