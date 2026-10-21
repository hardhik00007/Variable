package com.variable.viewers;

import com.variable.core.FileContext;
import com.variable.core.FileType;
import com.variable.core.FileViewer;
import com.variable.core.ViewerSession;
import com.variable.pdf.PageLayout;
import com.variable.pdf.PdfDocumentService;
import com.variable.pdf.PdfPageInfo;
import com.variable.pdf.PdfRenderCoordinator;
import com.variable.pdf.RenderScale;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.Duration;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * PDFBox-backed, per-tab PDF session.
 *
 * <p>Layout is held as a {@link PageLayout} model so scrolling finds the visible pages by binary search. Rendering
 * is delegated to a {@link PdfRenderCoordinator}, which owns the worker thread, the byte-bounded page cache and the
 * stale-result rules. Rasters are produced at {@code zoom × display output scale} and shown at the logical page
 * size, so Retina displays are sharp without enlarging the image a second time.
 */
public final class PdfFileViewer implements FileViewer {
    @Override
    public boolean supports(FileType type) {
        return type == FileType.PDF;
    }

    @Override
    public ViewerSession createSession(FileContext context) {
        return new PdfViewerSession(context);
    }

    private static final class PdfViewerSession implements ViewerSession {
        private static final double MIN_ZOOM = 0.5;
        private static final double MAX_ZOOM = 3.0;
        private static final double PAGE_GAP = 18;
        private static final double PAGE_PAD_TOP = 0;
        private static final double PAGE_PAD_BOTTOM = 32;
        private static final double PAGE_PAD_SIDE = 32;
        private static final int MAX_WANTED_PAGES = 6;
        private static final long CACHE_BUDGET_BYTES = 160L * 1024 * 1024;
        private static final Duration SCALE_DEBOUNCE = Duration.millis(140);

        private final AtomicBoolean closed = new AtomicBoolean();
        private final BorderPane root = new BorderPane();
        private final VBox pageList = new VBox(PAGE_GAP);
        private final ScrollPane scrollPane = new ScrollPane(pageList);
        private final Label pageCountLabel = new Label("Loading PDF…");
        private final Label zoomLabel = new Label();
        private final TextField pageField = new TextField();
        private final Button previous = new Button("‹");
        private final Button next = new Button("›");
        private final Button zoomOut = new Button("−");
        private final Button zoomIn = new Button("+");
        private final Button fitWidth = new Button("Fit width");
        private final List<PdfPageInfo> pages = new ArrayList<>();
        private final List<StackPane> slots = new ArrayList<>();
        private final List<Label> placeholders = new ArrayList<>();
        private final List<ImageView> views = new ArrayList<>();
        private final Set<Integer> populated = new HashSet<>();
        private final PageLayout layout = new PageLayout(PAGE_GAP);
        private final PauseTransition scaleDebounce = new PauseTransition(SCALE_DEBOUNCE);
        private final PdfRenderCoordinator<Image> coordinator;

        private volatile PdfDocumentService document;
        private int currentPage;
        private int keepFirst;
        private int keepLast = -1;
        private double zoom = 1.0;
        private boolean fittingWidth = true;
        private double outputScale = 1.0;
        private double laidOutWidth = -1;
        private boolean initialLayoutDone;
        private boolean updateQueued;
        private boolean relayoutQueued;

        private Scene observedScene;
        private Window observedWindow;
        private final ChangeListener<Scene> sceneListener = (observable, oldScene, scene) -> observeScene(scene);
        private final ChangeListener<Window> windowListener = (observable, oldWindow, window) -> observeWindow(window);
        private final ChangeListener<Number> outputScaleListener = (observable, oldValue, value) -> onOutputScaleChanged();

        private PdfViewerSession(FileContext context) {
            coordinator = new PdfRenderCoordinator<Image>(
                    this::renderOffUiThread,
                    image -> (long) (image.getWidth() * image.getHeight() * 4),
                    CACHE_BUDGET_BYTES,
                    Platform::runLater,
                    new PdfRenderCoordinator.Listener<Image>() {
                        @Override public void pageReady(int pageIndex, Image image) {
                            if (isKept(pageIndex)) showImage(pageIndex, image);
                        }

                        @Override public void pageFailed(int pageIndex) {
                            if (isKept(pageIndex)) showPageError(pageIndex);
                        }
                    },
                    "variable-pdf-renderer");
            scaleDebounce.setOnFinished(event -> applyScale());
            root.getStyleClass().add("pdf-viewer");
            root.setTop(buildToolbar());
            root.setCenter(buildLoadingState("Opening “" + context.fileName() + "”…"));
            root.sceneProperty().addListener(sceneListener);
            observeScene(root.getScene());
            disableControls(true);
            loadDocument(context);
        }

        @Override
        public Node view() {
            return root;
        }

        // ---- UI construction -------------------------------------------------------------------------------

        private Node buildToolbar() {
            var toolbar = new HBox(7);
            toolbar.getStyleClass().add("pdf-toolbar");
            previous.setOnAction(event -> goToPage(currentPage - 1));
            next.setOnAction(event -> goToPage(currentPage + 1));
            pageField.setPrefColumnCount(3);
            pageField.getStyleClass().add("pdf-page-field");
            pageField.setOnAction(event -> jumpToTypedPage());
            zoomOut.setOnAction(event -> changeZoom(1 / 1.2));
            zoomIn.setOnAction(event -> changeZoom(1.2));
            fitWidth.setOnAction(event -> fitToWidth());
            toolbar.getChildren().addAll(previous, next, pageField, pageCountLabel, spacer(), zoomOut, zoomLabel, zoomIn, fitWidth);
            return toolbar;
        }

        private Node spacer() {
            var spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            return spacer;
        }

        private Node buildLoadingState(String message) {
            var label = new Label(message);
            label.getStyleClass().add("viewer-message");
            var state = new StackPane(label);
            state.getStyleClass().add("pdf-loading-state");
            return state;
        }

        // ---- Loading ---------------------------------------------------------------------------------------

        private void loadDocument(FileContext context) {
            coordinator.runSerial(() -> {
                try {
                    PdfDocumentService opened = PdfDocumentService.open(context.path());
                    List<PdfPageInfo> loadedPages = new ArrayList<>();
                    for (int index = 0; index < opened.pageCount(); index++) loadedPages.add(opened.pageInfo(index));
                    if (closed.get()) {
                        opened.close();
                        return;
                    }
                    document = opened;
                    Platform.runLater(() -> finishLoading(loadedPages));
                } catch (Exception exception) {
                    Platform.runLater(() -> showDocumentError(context, exception));
                }
            });
        }

        private void finishLoading(List<PdfPageInfo> loadedPages) {
            if (closed.get()) return;
            pages.addAll(loadedPages);
            if (pages.isEmpty()) {
                showError("This PDF has no pages.");
                return;
            }
            pageList.getStyleClass().add("pdf-page-list");
            pageList.setAlignment(Pos.TOP_CENTER);
            pageList.setPadding(new Insets(PAGE_PAD_TOP, PAGE_PAD_SIDE, PAGE_PAD_BOTTOM, PAGE_PAD_SIDE));
            for (int index = 0; index < pages.size(); index++) {
                Label placeholder = new Label("Page " + (index + 1));
                placeholder.getStyleClass().add("pdf-page-placeholder");
                var slot = new StackPane(placeholder);
                slot.getStyleClass().add("pdf-page");
                slot.setMinSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
                slot.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
                placeholders.add(placeholder);
                slots.add(slot);
                views.add(null);
                pageList.getChildren().add(slot);
            }
            scrollPane.setFitToWidth(true);
            scrollPane.setPannable(false);
            scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
            scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
            scrollPane.getStyleClass().add("pdf-scroll-pane");
            scrollPane.viewportBoundsProperty().addListener((observable, previousBounds, bounds) -> onViewportBoundsChanged(bounds.getWidth()));
            // Scroll events can arrive many times per frame; they are coalesced into one update per pulse.
            scrollPane.vvalueProperty().addListener((observable, oldValue, value) -> requestViewportUpdate(false));
            root.setCenter(scrollPane);
            disableControls(false);
            setCurrentPage(0);
            onViewportBoundsChanged(scrollPane.getViewportBounds().getWidth());
        }

        // ---- Layout and viewport ---------------------------------------------------------------------------

        private void onViewportBoundsChanged(double width) {
            if (closed.get() || pages.isEmpty() || width <= 0) return;
            if (!initialLayoutDone) {
                initialLayoutDone = true;
                laidOutWidth = width;
                relayout();
                applyScale();
                return;
            }
            if (width != laidOutWidth && fittingWidth) {
                laidOutWidth = width;
                requestViewportUpdate(true);
                scaleDebounce.playFromStart();
            } else {
                requestViewportUpdate(false);
            }
        }

        /** Recomputes page sizes from the current zoom or fit-to-width. Runs at most once per pulse. */
        private void relayout() {
            double available = Math.max(120, scrollPane.getViewportBounds().getWidth() - PAGE_PAD_SIDE * 2);
            double[] heights = new double[pages.size()];
            for (int index = 0; index < heights.length; index++) {
                PdfPageInfo info = pages.get(index);
                double width = fittingWidth ? available : Math.min(info.width() * zoom, 2400);
                heights[index] = width * info.aspectRatio();
                slots.get(index).setPrefSize(width, heights[index]);
            }
            layout.update(heights);
            updateZoomLabel();
        }

        private void requestViewportUpdate(boolean relayout) {
            if (closed.get() || pages.isEmpty()) return;
            relayoutQueued |= relayout;
            if (updateQueued) return;
            updateQueued = true;
            Platform.runLater(this::flushViewportUpdate);
        }

        private void flushViewportUpdate() {
            updateQueued = false;
            if (closed.get() || pages.isEmpty()) return;
            if (relayoutQueued) {
                relayoutQueued = false;
                relayout();
            }
            updateViewport();
        }

        private double contentHeight() {
            return PAGE_PAD_TOP + layout.totalHeight() + PAGE_PAD_BOTTOM;
        }

        private double viewportHeight() {
            return scrollPane.getViewportBounds().getHeight();
        }

        /** Distance from the top of the page list to the top of the viewport, derived from the model, not the nodes. */
        private double scrollOffset() {
            return scrollPane.getVvalue() * Math.max(0, contentHeight() - viewportHeight());
        }

        /** Works out the current page and the few pages worth rendering. Cost is O(log n) plus the visible window. */
        private void updateViewport() {
            if (!initialLayoutDone || layout.pageCount() == 0) return;
            int count = layout.pageCount();
            double viewportHeight = Math.max(1, viewportHeight());
            double top = scrollOffset() - PAGE_PAD_TOP;
            int first = layout.firstVisible(top);
            int last = Math.max(first, layout.lastVisible(top + viewportHeight));
            setCurrentPage(layout.pageAt(top + 12));

            int from = Math.max(0, first - 1);
            int to = Math.min(Math.min(count - 1, last + 1), from + MAX_WANTED_PAGES * 3);
            double center = top + viewportHeight / 2;
            List<Integer> wanted = new ArrayList<>();
            for (int index = from; index <= to; index++) wanted.add(index);
            wanted.sort(Comparator.comparingDouble(index -> Math.abs(layout.topOf(index) + layout.heightOf(index) / 2 - center)));
            if (wanted.size() > MAX_WANTED_PAGES) wanted = new ArrayList<>(wanted.subList(0, MAX_WANTED_PAGES));

            keepFirst = Math.max(0, first - 2);
            keepLast = Math.min(count - 1, last + 2);
            releaseOutsideKeepRange();
            coordinator.request(wanted);
        }

        private boolean isKept(int pageIndex) {
            return !closed.get() && pageIndex >= keepFirst && pageIndex <= keepLast;
        }

        /** Only pages that actually hold an image are visited, never the whole document. */
        private void releaseOutsideKeepRange() {
            if (populated.isEmpty()) return;
            List<Integer> toRelease = null;
            for (int index : populated) {
                if (index < keepFirst || index > keepLast) {
                    if (toRelease == null) toRelease = new ArrayList<>();
                    toRelease.add(index);
                }
            }
            if (toRelease != null) for (int index : toRelease) clearSlot(index);
        }

        // ---- Navigation ------------------------------------------------------------------------------------

        private void goToPage(int pageIndex) {
            if (pageIndex < 0 || pageIndex >= pages.size() || layout.pageCount() == 0) return;
            setCurrentPage(pageIndex);
            double range = Math.max(1, contentHeight() - viewportHeight());
            scrollPane.setVvalue(Math.clamp((PAGE_PAD_TOP + layout.topOf(pageIndex)) / range, 0, 1));
            requestViewportUpdate(false);
        }

        private void setCurrentPage(int pageIndex) {
            currentPage = pageIndex;
            pageField.setText(Integer.toString(pageIndex + 1));
            previous.setDisable(pageIndex == 0);
            next.setDisable(pageIndex == pages.size() - 1);
        }

        private void jumpToTypedPage() {
            try {
                goToPage(Integer.parseInt(pageField.getText().trim()) - 1);
            } catch (NumberFormatException ignored) {
                pageField.setText(Integer.toString(currentPage + 1));
            }
        }

        // ---- Zoom and scale --------------------------------------------------------------------------------

        private void changeZoom(double multiplier) {
            if (pages.isEmpty()) return;
            zoom = Math.clamp(currentScale() * multiplier, MIN_ZOOM, MAX_ZOOM);
            fittingWidth = false;
            requestViewportUpdate(true);
            scaleDebounce.playFromStart();
        }

        private void fitToWidth() {
            if (pages.isEmpty()) return;
            fittingWidth = true;
            requestViewportUpdate(true);
            scaleDebounce.playFromStart();
        }

        /** Logical zoom: 1.0 means one PDF point per logical pixel. */
        private double currentScale() {
            if (!fittingWidth || pages.isEmpty()) return zoom;
            double available = Math.max(120, scrollPane.getViewportBounds().getWidth() - PAGE_PAD_SIDE * 2);
            return Math.clamp(available / pages.get(currentPage).width(), MIN_ZOOM, MAX_ZOOM);
        }

        private void updateZoomLabel() {
            zoomLabel.setText(String.format("%d%%", Math.round(currentScale() * 100)));
        }

        /** Raster scale to request: logical zoom times the display's output scale, rounded up to a coarse step. */
        private double desiredRenderScale() {
            return RenderScale.quantize(RenderScale.effective(currentScale(), outputScale));
        }

        /** Runs after zoom/resize/display changes settle. Only a material scale change drops cached renders. */
        private void applyScale() {
            if (closed.get() || !initialLayoutDone) return;
            if (relayoutQueued) {
                relayoutQueued = false;
                relayout();
            }
            double target = desiredRenderScale();
            if (Math.abs(target - coordinator.scale()) > 1e-6) coordinator.setScale(target);
            updateViewport();
        }

        // ---- Display scale (Retina / moving between monitors) ----------------------------------------------

        private void observeScene(Scene scene) {
            if (observedScene != null) observedScene.windowProperty().removeListener(windowListener);
            observedScene = scene;
            if (scene != null) {
                scene.windowProperty().addListener(windowListener);
                observeWindow(scene.getWindow());
            } else {
                observeWindow(null);
            }
        }

        private void observeWindow(Window window) {
            if (observedWindow != null) observedWindow.outputScaleXProperty().removeListener(outputScaleListener);
            observedWindow = window;
            if (window != null) window.outputScaleXProperty().addListener(outputScaleListener);
            onOutputScaleChanged();
        }

        private void onOutputScaleChanged() {
            if (closed.get()) return;
            double scale = observedWindow == null ? 1.0 : Math.max(1.0, observedWindow.getOutputScaleX());
            if (Math.abs(scale - outputScale) < 0.01) return;
            outputScale = scale;
            if (initialLayoutDone) scaleDebounce.playFromStart();
        }

        // ---- Page slots ------------------------------------------------------------------------------------

        private Image renderOffUiThread(int pageIndex, double scale) throws IOException {
            PdfDocumentService service = document;
            if (service == null) throw new IOException("The PDF is not open.");
            BufferedImage buffered = service.renderPage(pageIndex, scale);
            try {
                return SwingFXUtils.toFXImage(buffered, null);
            } finally {
                buffered.flush(); // Only the JavaFX copy is kept; the AWT raster is released immediately.
            }
        }

        private void showImage(int pageIndex, Image image) {
            ImageView view = views.get(pageIndex);
            if (view == null) {
                view = new ImageView();
                view.setPreserveRatio(true);
                view.setSmooth(true);
                // The fit size is the logical page size, so a 2x raster on a 2x display is drawn 1:1 in pixels.
                view.fitWidthProperty().bind(slots.get(pageIndex).widthProperty());
                view.fitHeightProperty().bind(slots.get(pageIndex).heightProperty());
                views.set(pageIndex, view);
                slots.get(pageIndex).getChildren().setAll(view);
            }
            view.setImage(image);
            populated.add(pageIndex);
        }

        private void showPageError(int pageIndex) {
            clearSlot(pageIndex);
            var error = new Label("Page " + (pageIndex + 1) + " could not be rendered.");
            error.getStyleClass().add("viewer-message");
            slots.get(pageIndex).getChildren().setAll(error);
            populated.add(pageIndex);
        }

        private void clearSlot(int pageIndex) {
            ImageView view = views.get(pageIndex);
            if (view != null) {
                view.fitWidthProperty().unbind();
                view.fitHeightProperty().unbind();
                view.setImage(null);
                views.set(pageIndex, null);
            }
            slots.get(pageIndex).getChildren().setAll(placeholders.get(pageIndex));
            populated.remove(pageIndex);
        }

        // ---- Errors and lifecycle --------------------------------------------------------------------------

        private void showDocumentError(FileContext context, Exception exception) {
            if (closed.get()) return;
            String message = exception.getMessage() != null && exception.getMessage().contains("password")
                    ? "This PDF is password-protected and cannot be opened yet."
                    : "Variable could not open “" + context.fileName() + "”. The file may be malformed or unreadable.";
            showError(message);
        }

        private void showError(String message) {
            root.setCenter(buildLoadingState(message));
            disableControls(true);
        }

        private void disableControls(boolean disabled) {
            previous.setDisable(disabled);
            next.setDisable(disabled);
            pageField.setDisable(disabled);
            zoomOut.setDisable(disabled);
            zoomIn.setDisable(disabled);
            fitWidth.setDisable(disabled);
            if (disabled) zoomLabel.setText("—");
            if (disabled) pageCountLabel.setText("Loading PDF…");
            else pageCountLabel.setText("of " + pages.size());
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            scaleDebounce.stop();
            root.sceneProperty().removeListener(sceneListener);
            observeScene(null);
            populated.clear();
            // The coordinator drops queued work, ignores in-flight results, and runs this cleanup on the worker
            // thread after any render in progress, so the document is never closed while PDFBox is using it.
            coordinator.close(() -> {
                PdfDocumentService service = document;
                if (service != null) {
                    try {
                        service.close();
                    } catch (IOException ignored) {
                        // The tab is already gone; there is no user-visible recovery action.
                    }
                }
            });
        }
    }
}
