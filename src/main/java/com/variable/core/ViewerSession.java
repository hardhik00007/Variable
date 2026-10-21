package com.variable.core;

import javafx.scene.Node;

/* Per-tab viewer state. Sessions that own native resources release them when their tab closes. */
public interface ViewerSession extends AutoCloseable {
    Node view();

    @Override
    default void close() {
        // Simple viewers have no explicit resources to release.
    }

    static ViewerSession of(Node view) {
        return () -> view;
    }
}
