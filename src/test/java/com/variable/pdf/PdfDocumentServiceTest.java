package com.variable.pdf;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfDocumentServiceTest {
    @TempDir Path tempDirectory;

    @Test void loadsMultiPageDocumentAndRendersPage() throws Exception {
        Path pdf = createPdf("sample.pdf", 2);
        try (PdfDocumentService service = PdfDocumentService.open(pdf)) {
            assertEquals(2, service.pageCount());
            assertTrue(service.pageInfo(0).width() > 0);
            var image = service.renderPage(0, 1.0);
            assertTrue(image.getWidth() > 0);
            assertTrue(image.getHeight() > 0);
        }
    }

    @Test void rejectsMalformedContent() throws IOException {
        Path malformed = Files.writeString(tempDirectory.resolve("broken.pdf"), "not a PDF");
        assertThrows(IOException.class, () -> PdfDocumentService.open(malformed));
    }

    @Test void rejectsPasswordProtectedDocument() throws Exception {
        Path pdf = tempDirectory.resolve("protected.pdf");
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            var policy = new StandardProtectionPolicy("owner-password", "user-password", new AccessPermission());
            policy.setEncryptionKeyLength(128);
            document.protect(policy);
            document.save(pdf.toFile());
        }
        assertThrows(IOException.class, () -> PdfDocumentService.open(pdf));
    }

    private Path createPdf(String name, int pageCount) throws IOException {
        Path pdf = tempDirectory.resolve(name);
        try (PDDocument document = new PDDocument()) {
            for (int index = 0; index < pageCount; index++) document.addPage(new PDPage());
            document.save(pdf.toFile());
        }
        return pdf;
    }
}
