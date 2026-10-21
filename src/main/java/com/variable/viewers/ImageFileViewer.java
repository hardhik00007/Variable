package com.variable.viewers;

import com.variable.core.FileContext;
import com.variable.core.FileType;
import com.variable.core.FileViewer;
import com.variable.core.ViewerSession;
import javafx.beans.binding.Bindings;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/** Displays a PNG or JPEG using JavaFX's background image loading support. */
public final class ImageFileViewer implements FileViewer {
    private static final double VIEW_PADDING = 32;

    @Override
    public boolean supports(FileType type) {
        return type == FileType.IMAGE;
    }

    @Override
    public ViewerSession createSession(FileContext context) {
        return ViewerSession.of(createView(context));
    }

    private Node createView(FileContext context) {
        var container = new StackPane();
        container.getStyleClass().add("image-viewer");

        var status = new Label("Opening image…");
        status.getStyleClass().add("viewer-message");
        container.getChildren().add(status);

        Image image = new Image(context.path().toUri().toString(), true);
        var imageView = new ImageView(image);
        imageView.setPreserveRatio(true);
        imageView.setSmooth(true);
        imageView.fitWidthProperty().bind(Bindings.max(0, container.widthProperty().subtract(VIEW_PADDING * 2)));
        imageView.fitHeightProperty().bind(Bindings.max(0, container.heightProperty().subtract(VIEW_PADDING * 2)));

        image.errorProperty().addListener((observable, wasError, hasError) -> {
            if (hasError) showError(container, context, image.getException());
        });
        image.progressProperty().addListener((observable, oldProgress, progress) -> {
            if (progress.doubleValue() >= 1 && !image.isError()) {
                container.getChildren().setAll(imageView);
            }
        });
        if (image.isError()) showError(container, context, image.getException());
        return container;
    }

    private void showError(StackPane container, FileContext context, Exception exception) {
        var title = new Label("Unable to open image");
        title.getStyleClass().add("image-error-title");
        var detail = new Label("“" + context.fileName() + "” is not a readable PNG or JPEG image.");
        detail.getStyleClass().add("viewer-message");
        detail.setWrapText(true);
        var message = new VBox(8, title, detail);
        message.getStyleClass().add("image-error");
        container.getChildren().setAll(message);
    }
}
