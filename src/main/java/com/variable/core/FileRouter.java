package com.variable.core;

import java.util.List;
import java.util.Optional;

/** Finds the registered viewer without coupling the workspace to parsing details. */
public final class FileRouter {
    private final List<FileViewer> viewers;

    public FileRouter(List<FileViewer> viewers) {
        this.viewers = List.copyOf(viewers);
    }

    public Optional<FileViewer> viewerFor(FileType type) {
        return viewers.stream().filter(viewer -> viewer.supports(type)).findFirst();
    }
}
