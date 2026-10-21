package com.variable.pdf;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

/** Thread-confined PDFBox document access used by one PDF viewer session at a time. */
public final class PdfDocumentService implements AutoCloseable {
    private final PDDocument document;
    private final PDFRenderer renderer;
    private volatile boolean closed;

    private PdfDocumentService(PDDocument document) throws IOException {
        this.document = document;
        if (document.isEncrypted()) {
            document.close();
            throw new IOException("This PDF is password-protected.");
        }
        this.renderer = new PDFRenderer(document);
    }

    public static PdfDocumentService open(Path path) throws IOException {
        return new PdfDocumentService(Loader.loadPDF(path.toFile()));
    }

    public int pageCount() {
        return document.getNumberOfPages();
    }

    public PdfPageInfo pageInfo(int pageIndex) {
        var page = document.getPage(pageIndex);
        // PDFBox renders the crop box, so layout must use it too or the raster and its slot would disagree.
        PDRectangle box = page.getCropBox();
        double width = box.getWidth();
        double height = box.getHeight();
        int rotation = Math.floorMod(page.getRotation(), 360);
        return rotation == 90 || rotation == 270 ? new PdfPageInfo(height, width) : new PdfPageInfo(width, height);
    }

    /**
     * Renders a page at {@code scale} (1.0 = 72 DPI, i.e. one PDF point per pixel). The scale is lowered if the
     * page would exceed the pixel budget in {@link RenderScale}, so very large pages or very high zoom cannot
     * allocate unbounded memory. Callers display the result at the logical page size, not its pixel size.
     */
    public BufferedImage renderPage(int pageIndex, double scale) throws IOException {
        if (closed) throw new IOException("This PDF has been closed.");
        PdfPageInfo info = pageInfo(pageIndex);
        double clamped = RenderScale.clampToBudget(scale, info.width(), info.height());
        return renderer.renderImageWithDPI(pageIndex, (float) (72 * clamped));
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() throws IOException {
        closed = true;
        document.close();
    }
}
