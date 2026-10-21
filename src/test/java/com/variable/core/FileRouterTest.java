package com.variable.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import com.variable.viewers.ImageFileViewer;
import com.variable.viewers.PdfFileViewer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileRouterTest {
    @Test void findsRegisteredViewerForSupportedType() {
        FileViewer viewer = new FileViewer() {
            public boolean supports(FileType type) { return type == FileType.TEXT; }
            public ViewerSession createSession(FileContext context) { return ViewerSession.of(null); }
        };
        FileRouter router = new FileRouter(List.of(viewer, new ImageFileViewer(), new PdfFileViewer()));
        assertTrue(router.viewerFor(FileType.TEXT).isPresent());
        assertTrue(router.viewerFor(FileType.IMAGE).isPresent());
        assertTrue(router.viewerFor(FileType.PDF).isPresent());
        assertFalse(router.viewerFor(FileType.UNKNOWN).isPresent());
    }
}
