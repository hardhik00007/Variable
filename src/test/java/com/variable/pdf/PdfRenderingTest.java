package com.variable.pdf;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real-PDF checks of the pixel mapping and of session teardown, using generated documents. */
class PdfRenderingTest {
    @TempDir Path tempDirectory;

    @Test void rasterSizeIsLogicalSizeTimesScaleSoRetinaPagesStaySharp() throws Exception {
        Path pdf = createPdf("a4.pdf", 1, new PDRectangle(600, 800), 0, null);
        try (var service = PdfDocumentService.open(pdf)) {
            PdfPageInfo info = service.pageInfo(0);
            assertEquals(600, info.width(), 0.5);
            assertEquals(800, info.height(), 0.5);
            for (double scale : new double[]{1.0, 1.5, 2.0, 3.0}) {
                BufferedImage image = service.renderPage(0, scale);
                assertEquals(info.width() * scale, image.getWidth(), 1.5, "width at " + scale);
                assertEquals(info.height() * scale, image.getHeight(), 1.5, "height at " + scale);
                System.out.println("  [metric] scale " + scale + " -> " + image.getWidth() + "x" + image.getHeight() + " px");
            }
        }
    }

    @Test void extremeScaleOnLargePageIsClampedToPixelBudget() throws Exception {
        Path pdf = createPdf("big.pdf", 1, new PDRectangle(3000, 3000), 0, null);
        try (var service = PdfDocumentService.open(pdf)) {
            BufferedImage image = service.renderPage(0, 4.0);
            long pixels = (long) image.getWidth() * image.getHeight();
            assertTrue(pixels <= RenderScale.MAX_PIXELS * 1.01, "pixels " + pixels);
            assertTrue(image.getWidth() < 3000 * 4.0);
            assertEquals(image.getWidth(), image.getHeight(), 1);   // aspect preserved
        }
    }

    @Test void rotatedAndCroppedPagesRenderAtTheSizeLayoutExpects() throws Exception {
        Path pdf = createPdf("rotated.pdf", 1, new PDRectangle(600, 800), 90, new PDRectangle(50, 50, 400, 500));
        try (var service = PdfDocumentService.open(pdf)) {
            PdfPageInfo info = service.pageInfo(0);
            assertEquals(500, info.width(), 0.5);    // 400x500 crop box, rotated 90 degrees
            assertEquals(400, info.height(), 0.5);
            BufferedImage image = service.renderPage(0, 2.0);
            assertEquals(info.width() * 2, image.getWidth(), 1.5);
            assertEquals(info.height() * 2, image.getHeight(), 1.5);
        }
    }

    @Test void renderingAfterCloseFailsCleanly() throws Exception {
        Path pdf = createPdf("closed.pdf", 1, PDRectangle.A4, 0, null);
        var service = PdfDocumentService.open(pdf);
        service.close();
        assertTrue(service.isClosed());
        assertThrows(IOException.class, () -> service.renderPage(0, 1.0));
    }

    @Test void closingASessionWithPendingRendersReleasesTheDocument() throws Exception {
        Path pdf = createPdf("many.pdf", 40, PDRectangle.A4, 0, null);
        var service = PdfDocumentService.open(pdf);
        var ui = new UiQueue();
        List<Integer> shown = Collections.synchronizedList(new ArrayList<>());
        var coordinator = new PdfRenderCoordinator<BufferedImage>(
                service::renderPage,
                image -> (long) image.getWidth() * image.getHeight() * 4,
                32L * 1024 * 1024, ui,
                new PdfRenderCoordinator.Listener<BufferedImage>() {
                    @Override public void pageReady(int pageIndex, BufferedImage value) { shown.add(pageIndex); }
                    @Override public void pageFailed(int pageIndex) { shown.add(-1); }
                },
                "test-pdf-renderer");

        coordinator.setScale(1.5);
        List<Integer> wanted = new ArrayList<>();
        for (int page = 0; page < 30; page++) wanted.add(page);
        coordinator.request(wanted);

        coordinator.close(() -> {
            try {
                service.close();
            } catch (IOException ignored) {
                // Test inspects the closed flag below.
            }
        });
        assertTrue(coordinator.awaitTermination(10, TimeUnit.SECONDS));
        int shownBeforeDrain = shown.size();
        ui.drain();

        assertTrue(service.isClosed(), "document must be closed once the worker finishes");
        assertEquals(shownBeforeDrain, shown.size(), "closed session must not update the UI");
        assertTrue(shown.isEmpty());
        assertEquals(0, coordinator.stats().cachedPages());
        var stats = coordinator.stats();
        // Every wanted page was either rendered before the close took effect or discarded unrendered.
        assertEquals(30, stats.rendered() + stats.pendingDiscarded());
        System.out.println("  [metric] closed with " + stats.rendered() + " rendered, " + stats.pendingDiscarded() + " discarded");
    }

    private Path createPdf(String name, int pageCount, PDRectangle size, int rotation, PDRectangle crop) throws IOException {
        Path pdf = tempDirectory.resolve(name);
        try (PDDocument document = new PDDocument()) {
            for (int index = 0; index < pageCount; index++) {
                PDPage page = new PDPage(size);
                page.setRotation(rotation);
                if (crop != null) page.setCropBox(crop);
                document.addPage(page);
            }
            document.save(pdf.toFile());
        }
        return pdf;
    }
}
