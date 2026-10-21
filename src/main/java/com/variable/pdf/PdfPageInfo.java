package com.variable.pdf;

/** Immutable page dimensions in PDF points, normalized for rotation. */
public record PdfPageInfo(double width, double height) {
    public double aspectRatio() {
        return height / width;
    }
}
