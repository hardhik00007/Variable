package com.variable.app;

import com.variable.core.FileContext;
import com.variable.core.FileViewer;
import com.variable.core.ViewerSession;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Owns document tabs and guarantees that one path has at most one open tab. */
public final class WorkspaceController {
    private final TabPane tabs;
    private final Map<Path, Tab> openTabs = new HashMap<>();

    public WorkspaceController(TabPane tabs) {
        this.tabs = tabs;
    }

    public void open(FileContext context, FileViewer viewer) {
        Path key = context.path().toAbsolutePath().normalize();
        Tab existing = openTabs.get(key);
        if (existing != null) {
            tabs.getSelectionModel().select(existing);
            return;
        }
        ViewerSession session = viewer.createSession(context);
        Tab tab = new Tab(context.fileName(), session.view());
        tab.setClosable(true);
        tab.setOnClosed(event -> {
            openTabs.remove(key);
            session.close();
        });
        openTabs.put(key, tab);
        tabs.getTabs().add(tab);
        tabs.getSelectionModel().select(tab);
    }
}
