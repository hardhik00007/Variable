# Variable

Variable is a lightweight, local desktop file viewer. It provides a carefully restrained JavaFX workspace for opening UTF-8 `.txt` files, PNG/JPEG images, and PDFs.

## Requirements

- JDK 21
- Maven 3.9+

## Build and run

```sh
mvn test
mvn javafx:run
```

## Current support and limitations

- Opens readable UTF-8 `.txt`, PNG, JPG, JPEG, and PDF files from the native file picker or by drag-and-drop.
- Re-opening a file selects its existing tab instead of creating a duplicate.
- Files exceeding 10 MiB are declined to protect the initial text viewer from excessive memory use.
- PNG and JPEG files are decoded by JavaFX in the background and scale proportionally as the viewing area changes.
- PDFs are rendered locally with PDFBox. The viewer supports vertical page scrolling, previous/next and direct page navigation, zoom (50–300%), and fit-to-width.
- PDF pages are rasterized at zoom × the display's output scale and shown at their logical size, so they stay sharp on Retina/HiDPI screens and when a window moves between displays.
- Password-protected PDFs show a clear unsupported state; Variable does not request or retain passwords in this phase.
- A PDF tab keeps rendered pages in a 160 MiB least-recently-used cache, renders only the visible pages plus a small margin, and releases its PDFBox document and renderer when closed. A single rendered page is capped at 12 million pixels.

## Architecture

The workspace (`app`) coordinates the user interface, while `core` validates, detects, and routes files. Format-specific rendering is isolated behind `FileViewer` and a per-tab `ViewerSession`, which gives resource-owning viewers a close hook. See [architecture notes](docs/ARCHITECTURE.md).

## Testing

`mvn test` verifies text, image, and PDF routing/detection, signature/extension conflicts, unknown and missing file handling, PDF loading, rendering, malformed input, and password protection. It also covers PDF render scheduling (cache reuse, scale invalidation, byte-budget eviction, stale-result rejection, closing with pending work), page-layout lookup, and the raster-size mapping on generated PDFs.

## Next steps

A context-aware global toolbar, recent files, and additional carefully scoped formats are planned. See [roadmap](docs/ROADMAP.md).
