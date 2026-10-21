package com.variable.app;

import com.variable.core.FileContext;
import com.variable.core.FileDetector;
import com.variable.core.FileRouter;
import com.variable.core.FileType;
import com.variable.viewers.ImageFileViewer;
import com.variable.viewers.PdfFileViewer;
import com.variable.viewers.TextFileViewer;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.TabPane;
import javafx.scene.input.DragEvent;
import javafx.scene.input.TransferMode;
import javafx.stage.FileChooser;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Coordinates user file-opening actions with the format-neutral workspace. */
public final class AppController {
    @FXML private TabPane workspaceTabs;
    @FXML private Label statusLabel;

    private final FileDetector detector = new FileDetector();
    private final FileRouter router = new FileRouter(List.of(new TextFileViewer(), new ImageFileViewer(), new PdfFileViewer()));
    private WorkspaceController workspace;

    @FXML
    private void initialize() {
        workspace = new WorkspaceController(workspaceTabs);
        showReadyState();
    }

    @FXML
    private void openFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open file in Variable");
        FileChooser.ExtensionFilter supportedFiles = new FileChooser.ExtensionFilter(
                "Supported files", "*.txt", "*.png", "*.jpg", "*.jpeg", "*.pdf");
        chooser.getExtensionFilters().addAll(
                supportedFiles,
                new FileChooser.ExtensionFilter("Text files", "*.txt"),
                new FileChooser.ExtensionFilter("Images", "*.png", "*.jpg", "*.jpeg"),
                new FileChooser.ExtensionFilter("PDF documents", "*.pdf"),
                new FileChooser.ExtensionFilter("All files", "*.*"));
        chooser.setSelectedExtensionFilter(supportedFiles);
        File chosen = chooser.showOpenDialog(workspaceTabs.getScene().getWindow());
        if (chosen != null) openPath(chosen.toPath());
    }

    @FXML
    private void handleDragOver(DragEvent event) {
        if (event.getDragboard().hasFiles()) event.acceptTransferModes(TransferMode.COPY);
        event.consume();
    }

    @FXML
    private void handleDrop(DragEvent event) {
        boolean opened = false;
        if (event.getDragboard().hasFiles()) {
            for (File file : event.getDragboard().getFiles()) {
                openPath(file.toPath());
                opened = true;
            }
        }
        event.setDropCompleted(opened);
        event.consume();
    }

    private void openPath(Path path) {
        try {
            validate(path);
            FileType type = detector.detect(path);
            var viewer = router.viewerFor(type);
            if (viewer.isEmpty()) {
                showError(path.getFileName() + " can’t be opened yet. Variable supports UTF-8 .txt, PNG, JPG, JPEG, and PDF files.");
                return;
            }
            FileContext context = new FileContext(path, type, path.getFileName().toString(), Files.size(path));
            workspace.open(context, viewer.get());
            statusLabel.setText(context.fileName() + " · " + context.sizeBytes() + " bytes");
        } catch (IOException | SecurityException exception) {
            showError("Couldn’t open “" + path.getFileName() + "”: " + readableMessage(exception));
        }
    }

    private void validate(Path path) throws IOException {
        if (!Files.exists(path)) throw new IOException("The file no longer exists.");
        if (!Files.isRegularFile(path)) throw new IOException("This is not a regular file.");
        if (!Files.isReadable(path)) throw new IOException("You do not have permission to read this file.");
    }

    private void showReadyState() {
        statusLabel.setText("Ready");
    }

    private void showError(String message) {
        statusLabel.setText(message);
    }

    private String readableMessage(Exception exception) {
        return exception.getMessage() == null ? "An unexpected error occurred." : exception.getMessage();
    }
}
