# Variable architecture

Variable owns the workspace; file viewers own their format-specific behavior.

```text
JavaFX UI → AppController → FileDetector → FileRouter → FileViewer → ViewerSession → JavaFX Node
```

The UI never parses file contents. `AppController` validates a dropped or selected path, `FileDetector` classifies it from safe signals, and `FileRouter` selects a registered viewer. A viewer creates a per-tab session containing the document node; `WorkspaceController` closes that session when its tab closes.

Detection is intentionally conservative: recognized PDF, PNG, and JPEG signatures override a misleading extension. Only valid, non-binary `.txt` files are offered to the text viewer, and only verified PNG/JPEG signatures are routed to the image viewer.

`TextFileViewer` reads files on a background task and updates the control on JavaFX's application thread through task event handlers. Its 10 MiB limit is a Phase 1 safeguard, not a claim that Variable cannot eventually support larger text files with streaming or virtualized viewing.

`ImageFileViewer` uses JavaFX `Image` background loading rather than opening an input stream on the application thread. Its `ImageView` binds its fit dimensions to the central viewer area while preserving aspect ratio. It retains no stream or external resource after loading; closing a tab removes the view and makes the decoded image eligible for garbage collection.

`PdfFileViewer` creates a `PdfViewerSession` for each tab. Rendering is split into small, separately tested parts in `com.variable.pdf`:

- `PageLayout` keeps page tops and heights as a model, so the current and visible pages are found by binary search rather than by scanning every page node on each scroll event.
- `PdfRenderCoordinator` owns the single worker thread (which also opens the document, so PDFBox is only touched by one thread), the pending queue, and the stale-result rules. Each viewport update *replaces* the queue with the few pages now wanted (visible pages plus one neighbour each side, at most six, nearest the viewport centre first), so work for pages the user has scrolled past is dropped before it starts. A render already running cannot be interrupted, but its result is discarded if the scale changed or the tab closed. A page already being rendered is not queued twice, and a page that failed is not retried until the scale changes.
- `PageRenderCache` is a least-recently-used cache bounded by bytes (160 MiB), not by page count, because image size varies widely with zoom and display scale. It is cleared when the raster scale changes.
- `RenderScale` holds the scale arithmetic. At zoom 1.0 one PDF point is one logical pixel. The raster scale is `zoom × output scale` (output scale comes from the JavaFX `Window` and is observed, so moving between displays re-renders), rounded *up* to a 0.05 step so tiny resizes do not trigger renders, and lowered if a page would exceed 12 million pixels or 8192 px on an edge. The `ImageView` is fitted to the logical page slot, so a 2× raster on a 2× display is drawn 1:1 and never enlarged a second time.

Scroll events are coalesced to one viewport update per JavaFX pulse. Zoom, fit-to-width, window resize and display-scale changes update page sizes immediately (the old image is stretched briefly) and re-render after a 140 ms debounce, so a burst of zoom clicks produces one render batch. Only slots that actually hold an image are visited when releasing distant pages. Closing the tab discards queued work, ignores in-flight results, and closes the `PDDocument` on the worker thread after any render in progress, so the document is never closed while PDFBox is using it.

`PdfDocumentService.pageInfo` uses the crop box because that is what PDFBox renders; using the media box would make the layout slot and the raster disagree for cropped pages.
