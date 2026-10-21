package com.variable;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;

/** Entry point for the Variable desktop application. */
public final class VariableApp extends Application {
    @Override
    public void start(Stage stage) throws IOException {
        var loader = new FXMLLoader(VariableApp.class.getResource("/com/variable/views/main-view.fxml"));
        var scene = new Scene(loader.load(), 1120, 760);
        scene.getStylesheets().add(VariableApp.class.getResource("/com/variable/styles/variable.css").toExternalForm());
        stage.setTitle("Variable");
        stage.setMinWidth(720);
        stage.setMinHeight(500);
        stage.setScene(scene);
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
