package com.variable.core;

/** Creates a per-document session for a supported, validated file. */
public interface FileViewer {
    boolean supports(FileType type);
    ViewerSession createSession(FileContext context);
}
