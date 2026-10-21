package com.variable.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Detects a file conservatively: known binary signatures take priority over extensions. */
public final class FileDetector {
    public FileType detect(Path path) throws IOException {
        byte[] header = readHeader(path);
        FileType signatureType = typeFromSignature(header);
        if (signatureType != FileType.UNKNOWN) {
            return signatureType;
        }
        return extension(path).equals("txt") && looksLikeText(header) ? FileType.TEXT : FileType.UNKNOWN;
    }

    private byte[] readHeader(Path path) throws IOException {
        try (var input = Files.newInputStream(path)) {
            return input.readNBytes(32);
        }
    }

    private FileType typeFromSignature(byte[] bytes) {
        if (startsWith(bytes, "%PDF-".getBytes())) return FileType.PDF;
        if (startsWith(bytes, new byte[]{(byte) 0x89, 'P', 'N', 'G'})) return FileType.IMAGE;
        if (startsWith(bytes, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF})) return FileType.IMAGE;
        return FileType.UNKNOWN;
    }

    private boolean looksLikeText(byte[] bytes) {
        for (byte value : bytes) if (value == 0) return false;
        return true;
    }

    private boolean startsWith(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (value[i] != prefix[i]) return false;
        return true;
    }

    private String extension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 1 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
