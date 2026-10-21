package com.variable.pdf;

/**
 * Pure arithmetic for choosing a PDF raster scale.
 *
 * <p>Mapping used throughout Variable: at logical zoom 1.0 one PDF point is one JavaFX logical pixel. A page is
 * therefore laid out {@code pointWidth * zoom} logical pixels wide. A display with output scale {@code s}
 * (2.0 on Retina) has {@code s} physical pixels per logical pixel, so a sharp raster needs
 * {@code pointWidth * zoom * s} pixels, i.e. a PDFBox scale (1.0 = 72 DPI) of {@code zoom * s}. The raster is then
 * shown in an {@code ImageView} fitted to the <em>logical</em> page size, so it is never enlarged a second time.
 */
public final class RenderScale {
    /** Rendering scales are rounded up to this step so tiny size changes do not trigger new renders. */
    public static final double STEP = 0.05;
    public static final double MIN_SCALE = 0.25;
    public static final double MAX_OUTPUT_SCALE = 4.0;
    /** Hard ceiling on pixels in one rendered page (about 48 MB as 32-bit pixels). */
    public static final long MAX_PIXELS = 12_000_000L;
    public static final int MAX_EDGE = 8192;

    private RenderScale() {
    }

    /** Raster scale wanted for a logical zoom on a display with the given output scale. */
    public static double effective(double logicalZoom, double outputScale) {
        double output = Double.isFinite(outputScale) ? Math.clamp(outputScale, 1.0, MAX_OUTPUT_SCALE) : 1.0;
        return Math.max(MIN_SCALE, logicalZoom * output);
    }

    /** Rounds up to the next {@link #STEP}, so a quantized raster is never coarser than requested. */
    public static double quantize(double scale) {
        double steps = Math.ceil(scale / STEP - 1e-9);
        return Math.round(steps * STEP * 1000.0) / 1000.0;
    }

    /** Lowers a scale, if needed, so a page of the given size stays inside the pixel budget. */
    public static double clampToBudget(double scale, double pageWidthPoints, double pageHeightPoints) {
        double wanted = Math.max(MIN_SCALE, scale);
        if (pageWidthPoints <= 0 || pageHeightPoints <= 0) return wanted;
        double byEdge = MAX_EDGE / Math.max(pageWidthPoints, pageHeightPoints);
        double byArea = Math.sqrt(MAX_PIXELS / (pageWidthPoints * pageHeightPoints));
        return Math.min(wanted, Math.min(byEdge, byArea));
    }

    public static int pixelSize(double scale, double pointSize) {
        return (int) Math.max(1, Math.ceil(pointSize * scale));
    }
}
