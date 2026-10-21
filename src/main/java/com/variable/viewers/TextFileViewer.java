package com.variable.viewers;

import com.variable.core.FileContext;
import com.variable.core.FileType;
import com.variable.core.FileViewer;
import com.variable.core.ViewerSession;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.StackPane;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** UTF-8 text viewer with a conservative 10 MiB limit for the initial release. */
public final class TextFileViewer implements FileViewer {
    private static final long MAX_TEXT_FILE_SIZE = 10L * 1024 * 1024;

    @Override
    public boolean supports(FileType type) {
        return type == FileType.TEXT;
    }

    @Override
    public ViewerSession createSession(FileContext context) {
        return ViewerSession.of(createView(context));
    }

    private Node createView(FileContext context) {
        if (context.sizeBytes() > MAX_TEXT_FILE_SIZE) {
            return message("This text file is larger than 10 MiB and was not opened.");
        }

        var text = new TextArea();
        text.getStyleClass().add("document-text");
        text.setEditable(false);
        text.setWrapText(false);
        text.setPromptText("Opening \"" + context.fileName() + "\"…");

        Task<String> loadTask = new Task<>() {
            @Override protected String call() throws Exception {
                return Files.readString(context.path(), StandardCharsets.UTF_8);
            }
        };
        loadTask.setOnSucceeded(event -> text.setText(loadTask.getValue()));
        loadTask.setOnFailed(event -> text.setText("Unable to read this text file.\n\n" + loadTask.getException().getMessage()));
        Thread thread = new Thread(loadTask, "variable-text-reader");
        thread.setDaemon(true);
        thread.start();
        return text;
    }

    private Node message(String value) {
        var label = new Label(value);
        label.getStyleClass().add("viewer-message");
        var pane = new StackPane(label);
        pane.setPadding(new Insets(32));
        return pane;
    }
}
