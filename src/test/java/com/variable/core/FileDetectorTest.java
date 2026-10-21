package com.variable.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FileDetectorTest {
    private final FileDetector detector = new FileDetector();

    @TempDir Path tempDirectory;

    @Test void detectsUtf8TextFile() throws IOException {
        Path file = Files.writeString(tempDirectory.resolve("notes.txt"), "A short note.");
        assertEquals(FileType.TEXT, detector.detect(file));
    }

    @Test void signatureWinsOverMisleadingTextExtension() throws IOException {
        Path file = Files.write(tempDirectory.resolve("renamed.txt"), "%PDF-1.7".getBytes());
        assertEquals(FileType.PDF, detector.detect(file));
    }

    @Test void unknownExtensionIsNotTreatedAsText() throws IOException {
        Path file = Files.writeString(tempDirectory.resolve("notes.bin"), "Readable but unsupported");
        assertEquals(FileType.UNKNOWN, detector.detect(file));
    }

    @Test void detectsPngBySignatureRegardlessOfExtension() throws IOException {
        Path file = Files.write(tempDirectory.resolve("photo.jpeg"), new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10});
        assertEquals(FileType.IMAGE, detector.detect(file));
    }

    @Test void detectsJpegBySignatureRegardlessOfExtension() throws IOException {
        Path file = Files.write(tempDirectory.resolve("photo.png"), new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0});
        assertEquals(FileType.IMAGE, detector.detect(file));
    }

    @Test void rejectsImageExtensionWithoutSupportedSignature() throws IOException {
        Path file = Files.writeString(tempDirectory.resolve("not-an-image.png"), "plain text");
        assertEquals(FileType.UNKNOWN, detector.detect(file));
    }

    @Test void missingFileProducesIoError() {
        assertThrows(IOException.class, () -> detector.detect(tempDirectory.resolve("missing.png")));
    }
}
