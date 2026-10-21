package com.variable.core;

import java.nio.file.Path;

/** A validated file and the result of its format detection. */
public record FileContext(Path path, FileType type, String fileName, long sizeBytes) {
}
